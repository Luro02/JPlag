package de.jplag.reporting.reportobject.model;

import java.util.List;

/**
 * Represents a matched code segment between two files. Stores metadata about the matching code positions in both files.
 * @param firstFileName Name of the first file involved in the match
 * @param secondFileName Name of the second file involved in the match
 * @param startInFirst Start position of the match in the first file
 * @param endInFirst End position of the match in the first file
 * @param startInSecond Start position of the match in the second file
 * @param endInSecond End position of the match in the second file
 * @param lengthOfFirst Length of the matched segment in the first file
 * @param lengthOfSecond Length of the matched segment in the second file
 * @param transformationsInFirst Distinct transformation names associated with matched tokens in the first file, in
 * encounter order
 * @param transformationsInSecond Distinct transformation names associated with matched tokens in the second file, in
 * encounter order
 */
public record Match(String firstFileName, String secondFileName, CodePosition startInFirst, CodePosition endInFirst, CodePosition startInSecond,
        CodePosition endInSecond, int lengthOfFirst, int lengthOfSecond, List<String> transformationsInFirst, List<String> transformationsInSecond) {

    /**
     * Creates a match without transformation metadata.
     */
    public Match(String firstFileName, String secondFileName, CodePosition startInFirst, CodePosition endInFirst, CodePosition startInSecond,
            CodePosition endInSecond, int lengthOfFirst, int lengthOfSecond) {
        this(firstFileName, secondFileName, startInFirst, endInFirst, startInSecond, endInSecond, lengthOfFirst, lengthOfSecond, List.of(),
                List.of());
    }
}
