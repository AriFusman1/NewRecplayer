package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.rules.CompiledRules;
import com.pcapplayer.autotag.rules.RuleMatchAnalyzer;
import com.pcapplayer.autotag.rules.RuleOutcome;
import com.pcapplayer.icd.analysis.MessageCountAnalyzer;
import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.EDecodeProblem;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.capture.CorruptInputException;
import com.pcapplayer.pcapProcessing.capture.InvalidCaptureException;
import com.pcapplayer.pcapProcessing.capture.OpenedPcap;
import com.pcapplayer.pcapProcessing.capture.PcapInputOpener;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.extract.MessageExtractor;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.pcapProcessing.summary.CaptureSummary;
import com.pcapplayer.recordManagement.model.MessageTypeCount;
import com.pcapplayer.recordManagement.model.TaggingStats;
import com.pcapplayer.recordManagement.service.RecordService;
import com.pcapplayer.recordManagement.service.TaxonomyService;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tags one file of a job:
 * <ol>
 *   <li>open it ({@link PcapInputOpener}: gunzip if needed, check the magic number) into a temp file,</li>
 *   <li>scan it and find its server ports ({@link ServicePorts}),</li>
 *   <li>decode the whole capture once, feeding the message counts, the rules and the port collector,</li>
 *   <li>if a rule matched and the run is not evaluate-only, save it ({@link RecordService#saveTagged}).</li>
 * </ol>
 * At most one decompressed file per call is on disk; it is deleted at the end whatever happens.
 * Problems with one file become its result and never escape.
 */
final class FileTagger {

    private static final Logger LOG = Logger.getLogger(FileTagger.class.getName());

    /** Everything a job's files share. */
    record Context(TagJob job, EConnectionMode mode, IcdDefinition icd, CompiledRules rules, JobFileSource source, Path workDir,
                   LiveJob live) {
    }

    private final ExtractionSettings settings;
    private final CaptureScanner scanner;
    private final MessageExtractor extractor;
    private final RecordService records;
    private final TaxonomyService taxonomy;
    private final Object saveLock;
    private final Clock clock;

    /** @param saveLock saves of all jobs take turns, so two files with the same content cannot both create a record */
    FileTagger(ExtractionSettings settings, RecordService records, TaxonomyService taxonomy, Object saveLock, Clock clock) {
        this.settings = settings;
        this.scanner = new CaptureScanner(settings);
        this.extractor = new MessageExtractor(settings); // not the shared cache: these scripts are never replayed
        this.records = records;
        this.taxonomy = taxonomy;
        this.saveLock = saveLock;
        this.clock = clock;
    }

    /** @return the file's result, or null when the job was cancelled while the file was in flight (abandoned) */
    FileResult process(Context context, int index, JobInput input) {
        Path work = context.workDir().resolve("work-" + index + ".pcap");
        boolean gzipped = false;

        try {
            OpenedPcap opened;

            try (InputStream in = context.source().open(index, input)) {
                opened = PcapInputOpener.open(in, input.name(), work);
            }
            gzipped = opened.gzipped();
            if (!opened.isPcap()) return FileResult.notAPcap(index, input, gzipped);
            return decodeAndSave(context, index, input, opened);
        } catch (CancellationException | InterruptedIOException e) {
            return null;
        } catch (CorruptInputException e) {
            return FileResult.failed(index, input, true, e.getMessage());
        } catch (InvalidCaptureException e) {
            return FileResult.failed(index, input, gzipped, "Corrupt capture: " + e.getMessage());
        } catch (IOException e) {
            if (context.live().cancelled.get()) return null;
            return FileResult.failed(index, input, gzipped, "Cannot read the file: " + e.getMessage());
        } catch (RuntimeException e) {
            if (context.live().cancelled.get()) return null;
            LOG.log(Level.WARNING, "Tagging " + input.path() + " failed", e);
            return FileResult.failed(index, input, gzipped, String.valueOf(e.getMessage()));
        } finally {
            deleteQuietly(work);
        }
    }

    private FileResult decodeAndSave(Context context, int index, JobInput input, OpenedPcap opened) throws IOException {
        CaptureSummary summary = scanner.scan(opened.path());
        Set<Integer> ports = ServicePorts.find(opened.path(), context.mode(), settings);
        MessageCountAnalyzer counts = new MessageCountAnalyzer();
        RuleMatchAnalyzer rules = new RuleMatchAnalyzer(context.rules(), context.live().cancelled::get);
        DecodedPortsAnalyzer decodedPorts = new DecodedPortsAnalyzer(context.mode());
        DecodeStats stats;

        if (ports.isEmpty()) {
            stats = ScriptDecoder.decode(emptyScript(context.mode()), 0, context.icd(), counts, rules, decodedPorts);
        } else {
            ExtractionFilter filter = ExtractionFilter.ofMicros(context.mode(), ports, null, summary.startUs(), summary.endUs());
            ReplayScript script = extractor.extract(opened.path(), filter).script();
            stats = ScriptDecoder.decode(script, filter.startNanos(), context.icd(), counts, rules, decodedPorts);
        }
        if (context.live().cancelled.get()) throw new CancellationException();

        RuleOutcome outcome = rules.outcome();
        List<MessageTypeCount> typeCounts = counts.counts().stream()
                .map(count -> new MessageTypeCount(count.opcode(), count.name(), count.count())).toList();
        long parseErrors = stats.count(EDecodeProblem.PARSE_ERROR);
        long unknown = stats.count(EDecodeProblem.UNKNOWN_OPCODE);
        EFileStatus status;
        List<String> recordIds = List.of();

        if (!outcome.matched()) {
            status = EFileStatus.NOT_MATCHED;
        } else if (context.job().evaluateOnly()) {
            status = EFileStatus.MATCHED;
        } else {
            TaggingStats tagging = new TaggingStats(context.icd().id(), stats.messages(), typeCounts, parseErrors, unknown,
                    outcome.matchedRules(), context.job().id(), clock.millis());
            RecordService.TaggedSave saved = save(context, opened, summary, decodedPorts.ports(), outcome, tagging);
            status = saved.updatedExisting() ? EFileStatus.MATCHED_EXISTING_UPDATED : EFileStatus.MATCHED_SAVED;
            recordIds = saved.recordIds();
        }
        return new FileResult(index, input.name(), input.path(), input.size(), opened.gzipped(), status, stats.messages(), typeCounts,
                parseErrors, unknown, outcome.matchedRules(), recordIds, null);
    }

    /**
     * The record covers the service ports where ICD messages were decoded, from the first message
     * that counted for a matched rule to the end of the capture.
     */
    private RecordService.TaggedSave save(Context context, OpenedPcap opened, CaptureSummary summary, List<Integer> ports,
                                          RuleOutcome outcome, TaggingStats tagging) {
        long startUs = Math.max(summary.startUs(), TimeUnit.NANOSECONDS.toMicros(outcome.firstMatchNanos()));
        String description = "Auto-tagged: " + String.join(", ", outcome.matchedRules());

        synchronized (saveLock) {
            List<String> tagIds = taxonomy.ensureTags(outcome.matchedRules());
            return records.saveTagged(new RecordService.TaggedCapture(opened.path(), opened.name(), opened.size(), opened.sha256(),
                    summary, context.job().categoryId(), ports, startUs, summary.endUs(), description, tagIds, tagging,
                    context.job().createdBy()));
        }
    }

    private static ReplayScript emptyScript(EConnectionMode mode) {
        return new ReplayScript(mode, List.of(), 0, 0, 0, List.of());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a temp file
        }
    }
}
