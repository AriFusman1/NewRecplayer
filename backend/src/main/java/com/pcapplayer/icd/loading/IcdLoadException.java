package com.pcapplayer.icd.loading;

import java.util.List;
import java.util.stream.Collectors;

/** An ICD could not be loaded. Carries every problem found, not just the first. */
public class IcdLoadException extends Exception {

    private final transient List<IcdProblem> problems;

    public IcdLoadException(List<IcdProblem> problems) {
        super(problems.stream().map(IcdProblem::toString).collect(Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<IcdProblem> problems() {
        return problems;
    }
}
