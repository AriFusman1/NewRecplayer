package com.pcapplayer.autotag.job;

import java.io.IOException;
import java.io.InputStream;

/** Opens a job's input files: uploaded copies on local disk, or objects in the archive. */
@FunctionalInterface
interface JobFileSource {

    InputStream open(int index, JobInput input) throws IOException;
}
