package de.pfeifer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Small utility to count features of proof scripts such as number of various commands. Since this
 * does not use a real parser, the counting functions relies on simple String matching and may be
 * wrong in some cases, so make sure to sanity-check the results!
 * This class is tailored to Sorter.java of the ips4o case study at the moment!
 *
 * @author Wolfram Pfeifer
 */
public class ScriptCounter {

    private static final String JML_LINE_START = "//@";
    private static final String JML_START = "/*@";
    private static final String JML_END = "*/";

    /**
     * Commands that should be counted. To be counted correctly, they need to be at the beginning of
     * a line (leading @ is allowed)!
     */
    private static final Set<String> VALID_COMMANDS = Set.of("oss", "macro", "rule", "expand", "witness", "auto", "tryclose", "cut", "assert", "leave", "cheat", "let");

    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            System.err.println("No project directory given!");
            System.exit(-1);
        }

        final Path projDir = Paths.get(args[0]);
        final Path input = projDir.resolve(Paths.get("src/main/java/de/wiesler/Sorter.java"));
        final Path outputDir = projDir.resolve(Paths.get("src/main/script"));

        String content = Files.readString(input);
        List<JmlAnnotation> annotations = obtainJML(content);
        List<JmlAnnotation> filteredAnnotations = filterAnnotations(annotations);

        List<ScriptStat> stats = new ArrayList<>();
        filteredAnnotations.stream()
            .filter(a -> a.type().equals(Type.ASSERT_SCRIPT))
            .map(ScriptCounter::countCommands).forEach(stats::add);

        List<String> methodPrefixes = List.of(
            "sort(int[] values)",
            "sample(int[] values, int begin, int end, Storage storage)",
            "fallback_sort(",
            "sample_sort_recurse_on");

        Map<String, ScriptStat> accumulatedStats = new HashMap<>();
        for (String method : methodPrefixes) {
            List<ScriptStat> singleMethod = stats.stream().filter(s -> s.jml.methodName.startsWith(method)).toList();
            //singleMethod.stream().forEach(System.out::println);
            accumulatedStats.put(method, accumulate(singleMethod));
            printAnnotations(singleMethod.stream().map(s -> s.jml).toList(), outputDir.resolve("jml"));
            //System.out.println(methodName + " " + accumulate(singleMethod));
        }

        String csv = toCsvString(accumulatedStats);
        System.out.println(csv);

        Path csvOut = outputDir.resolve("stats.csv");
        Files.deleteIfExists(csvOut);
        Files.createFile(csvOut);
        Files.writeString(csvOut, csv);
    }

    private static String toCsvString(Map<String, ScriptStat> accumulatedStats) {
        final String separator = ";";
        // header
        StringBuilder result = new StringBuilder("method name");
        List<String> sortedCommands = VALID_COMMANDS.stream().sorted().toList();
        result.append(sortedCommands.stream().reduce("", (s, t) -> s + separator + t));
        result.append(System.lineSeparator());

        for (var c : accumulatedStats.entrySet()) {
            result.append(c.getKey()).append(separator);
            var map = c.getValue().commandCount;
            for (var key : sortedCommands) {
                result.append(map.getOrDefault(key, 0)).append(separator);
            }
            result.append(System.lineSeparator());
        }
        return result.toString();
    }

    private static ScriptStat accumulate(List<ScriptStat> stats) {
        return stats.stream().reduce(new ScriptStat(null, Map.of()), ScriptCounter::combine);
    }

    private static ScriptStat combine(ScriptStat s, ScriptStat t) {
        HashMap<String, Integer> res = new HashMap<>();
        s.commandCount.forEach(
            (key, value) -> res.put(key, value + t.commandCount.getOrDefault(key, 0)));
        // make sure to add also entries that are only present in t
        t.commandCount().forEach(
            (key, value) -> res.putIfAbsent(key, value + s.commandCount.getOrDefault(key, 0)));
        return new ScriptStat(null, res);
    }

    private static ScriptStat countCommands(JmlAnnotation a) {
        Map<String, Integer> stats = new HashMap<>();
        a.content.lines().forEach(
            s -> {
                String noDelim = s.trim().startsWith("@") ? s.trim().substring(1) : s.trim();
                for (String command : VALID_COMMANDS) {
                    if (noDelim.startsWith(command)) {
                        if (stats.containsKey(command)) {
                            stats.put(command, stats.get(command) + 1);
                        } else {
                            stats.put(command, 1);
                        }
                    }
                }
            }
        );
        return new ScriptStat(a, stats);
    }

    /**
     * Filters blank lines (also those that contain only @) and comments from JML.
     * @param annotations
     * @return
     */
    private static List<JmlAnnotation> filterAnnotations(List<JmlAnnotation> annotations) {
        List<JmlAnnotation> filtered = new ArrayList<>();
        for (JmlAnnotation a : annotations) {
            String filteredAnnotation = a.content.lines()
                .filter(s -> !s.isBlank())
                .filter(s -> !s.trim().equals("@"))
                .filter(s -> !(s.trim().startsWith("//") && !s.trim().startsWith("//@")))
                .filter(s -> !(s.trim().startsWith("@ //")))
                .collect(Collectors.joining(System.lineSeparator()));

            filtered.add(new JmlAnnotation(a.methodName, filteredAnnotation, a.type));
        }
        return filtered;
    }

    /**
     * Writes the collected annotations into separate files.
     * @param annotations
     * @param outputDir
     * @throws IOException
     */
    private static void printAnnotations(List<JmlAnnotation> annotations, Path outputDir) throws IOException {
        for (var entry : annotations) {
            int counter = 0;
            Path out = outputDir.resolve("Sorter_" + toValidFileName(entry.methodName) + "_"  + counter + ".txt");
            while (Files.exists(out)) {
                counter++;
                out = outputDir.resolve("Sorter_" + toValidFileName(entry.methodName) + "_"  + counter + ".txt");
            }
            Files.createFile(out);
            Files.writeString(out, entry.content + System.lineSeparator());
        }
    }

    /**
     * Writes the script statistics into separate files.
     * @param stats
     * @param outputDir
     * @throws IOException
     */
    private static void printStats(List<ScriptStat> stats, Path outputDir) throws IOException {
        for (var entry : stats) {
            int counter = 0;
            Path out = outputDir.resolve("Sorter_" + toValidFileName(entry.jml.methodName) + "_" + counter + ".txt");
            while (Files.exists(out)) {
                counter++;
                out = outputDir.resolve("Sorter_" + toValidFileName(entry.jml.methodName) + "_" + counter + ".txt");
            }
            Files.createFile(out);
            Files.writeString(out, entry.jml.content + System.lineSeparator());
        }
    }

    /**
     * One single JML entity, e.g. an invariant, a contract, an assert w or w/o script, ...
     * @param methodName
     * @param content
     * @param type
     */
    record JmlAnnotation(String methodName, String content, Type type) { }

    /**
     * Number of each command occurring in the JML entity.
     * @param jml
     * @param commandCount
     */
    record ScriptStat(JmlAnnotation jml, Map<String, Integer> commandCount) { }

    /**
     * Type of a JML entity.
     */
    enum Type {
        ASSERT,
        ASSERT_SCRIPT,
        ANNOTATION,
        CONTRACT,
        GHOST_DECL,
        LOOP_INV,
        MODEL_METHOD,
        SET_STATEMENT,
        UNKNOWN
    }

    private static List<JmlAnnotation> obtainJML(final String input) {
        List<JmlAnnotation> jml = new ArrayList<>();

        String methodName = "";
        String methodNameSig = "";
        int braceLevel = 0;

        int jmlStart = -1;
        for (int i = 0; i < input.length(); i++) {
            if (input.startsWith(JML_START, i)) {
                jmlStart = i;
                // skip to line end
                int j = 0;
                while (!input.startsWith(JML_END, i + j)) {
                    j++;
                }
                String content = input.substring(jmlStart, jmlStart+j+2);
                Type type = determineAnnotationType(content);
                jml.add(new JmlAnnotation(methodNameSig, content, type));
                //jmlStart = -1;

            } else if (input.startsWith(JML_LINE_START, i)) {
                jmlStart = i;

                // skip to line end
                int j = 0;
                while (!input.startsWith(System.lineSeparator(), i + j)) {
                    j++;
                }
                String content = input.substring(jmlStart, jmlStart+j);
                Type type = determineAnnotationType(content);
                jml.add(new JmlAnnotation(methodNameSig, content, type));
                //jmlStart = -1;

            } else if (input.charAt(i) == '{') {
                braceLevel++;

                if (braceLevel == 2) {

                    int j = 0;
                    while (input.charAt(i - j) != '(') {
                        j++;
                    }
                    int k = j;
                    while (input.charAt(i - k) != ' ') {
                        k++;
                    }
                    methodName = input.substring(i - k, i - j).trim();
                    String sig = input.substring(i - j, i).trim();
                    sig = sig.replaceAll("\\s+", " ");
                    methodNameSig = methodName + sig;

                    // debug output
                    /*
                    if (jmlStart > 0) {
                        System.out.println("entering model methodName " + methodNameSig);
                    } else {
                        System.out.println("entering methodName " + methodNameSig);
                    }*/
                }
            } else if (input.charAt(i) == '}') {
                braceLevel--;
            } else if (input.startsWith(JML_END, i)) {
                jmlStart = -1;
            }
        }
        return jml;
    }

    private static Type determineAnnotationType(String content) {

        String noDelim = content.substring(3).trim();

        if (noDelim.startsWith("assert")) {
//            if (noDelim.split("\n")[0].trim().contains("\\by")) {   // too restrictive!
            if (noDelim.contains("\\by")) {         // TODO: also matches in comments ...
                return Type.ASSERT_SCRIPT;
            } else {
                return Type.ASSERT;
            }
        } else if (noDelim.startsWith("set ")) {
            return Type.SET_STATEMENT;
        } else if (noDelim.startsWith("loop_invariant")) {
            return Type.LOOP_INV;
        } else if (noDelim.contains("requires ")) {
            return Type.CONTRACT;
        } else if (noDelim.contains("model ")) {
            return Type.MODEL_METHOD;
        } else if (noDelim.contains("ghost ")) {
            return Type.GHOST_DECL;
        } else if (noDelim.contains("pure") || noDelim.contains("non_null")
            || noDelim.contains("nullable")) {
            return Type.ANNOTATION;
        }
        return Type.UNKNOWN;
    }

    private static String toValidFileName(String s) {
        s = s.replace("\\", "_")
            .replace("$", "_")
            .replace("?", "_")
            .replace("|", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace(":", "_")
            .replace("*", "+")
            .replace("\"", "'")
            .replace("/", "-")
            .replace("[", "(")
            .replace("]", ")");
        return s;
    }
}
