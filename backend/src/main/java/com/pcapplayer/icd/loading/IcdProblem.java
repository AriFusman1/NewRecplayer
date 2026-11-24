package com.pcapplayer.icd.loading;

/**
 * One thing wrong with an ICD.
 *
 * @param path where: a JSON path ({@code $.messages[1].fields[2].array}) for JSON ICDs, a Java location
 *             ({@code TrackMessage.targets}) for annotated ones
 */
public record IcdProblem(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
