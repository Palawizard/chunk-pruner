package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.report.Storage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The resume journal of a prune run (SPEC section 4, steps 6 and 7). Two files in
 * {@code <world>/chunkpruner/}: {@code journal-plan.json}, written once before the first rewrite,
 * holds what each affected file loses and the report of the run; {@code journal.json} holds the
 * state of each region, {@code pending} or {@code done}, and is written again after each region.
 * The plan is the larger file, so it is not rewritten for every region. Both are written
 * atomically, and the journal exists exactly while a run is unfinished.
 */
final class Journal {
    static final int FORMAT = 1;
    private static final String PENDING = "pending";
    private static final String DONE = "done";

    /** One storage file of a region: the entries it loses and its sizes before and after. */
    record FilePlan(
            Storage storage,
            Set<Integer> removed,
            long sizeBefore,
            long modifiedBefore,
            long sizeAfter,
            List<String> externalFiles) {}

    /** One region, with its {@code region/} file first and then its counterparts. */
    record RegionPlan(String dimension, String folder, String file, List<FilePlan> files) {}

    /** {@code backup} is relative to the world folder, or null when the config turned it off. */
    record Plan(String runId, String backup, List<RegionPlan> regions, Map<String, Object> report, String reportText) {}

    private Journal() {}

    static Path file(Path world) {
        return world.resolve("chunkpruner").resolve("journal.json");
    }

    static Path planFile(Path world) {
        return world.resolve("chunkpruner").resolve("journal-plan.json");
    }

    static boolean exists(Path world) {
        return Files.exists(file(world));
    }

    /** Writes the plan, then the journal with every region pending. */
    static void start(Path world, Plan plan) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("runId", plan.runId());
        root.put("backup", plan.backup());
        List<Object> regions = new ArrayList<>();
        for (RegionPlan region : plan.regions()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dimension", region.dimension());
            r.put("folder", region.folder());
            r.put("file", region.file());
            List<Object> files = new ArrayList<>();
            for (FilePlan f : region.files()) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("storage", f.storage().folder());
                file.put("remove", bits(f.removed()));
                file.put("sizeBefore", f.sizeBefore());
                file.put("modifiedBefore", f.modifiedBefore());
                file.put("sizeAfter", f.sizeAfter());
                file.put("externalFiles", f.externalFiles());
                files.add(file);
            }
            r.put("files", files);
            regions.add(r);
        }
        root.put("regions", regions);
        root.put("report", plan.report());
        root.put("reportText", plan.reportText());
        AtomicFiles.writeString(planFile(world), Json.write(root));
        writeStates(world, plan, new HashSet<>());
    }

    static void markDone(Path world, Plan plan, Set<Integer> done) throws IOException {
        writeStates(world, plan, done);
    }

    private static void writeStates(Path world, Plan plan, Set<Integer> done) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("runId", plan.runId());
        root.put("plan", planFile(world).getFileName().toString());
        List<Object> regions = new ArrayList<>();
        for (int i = 0; i < plan.regions().size(); i++) {
            RegionPlan region = plan.regions().get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dimension", region.dimension());
            r.put("file", region.file());
            r.put("state", done.contains(i) ? DONE : PENDING);
            regions.add(r);
        }
        root.put("regions", regions);
        AtomicFiles.writeString(file(world), Json.write(root));
    }

    /** The plan and the indices of its regions marked done. */
    record Loaded(Plan plan, Set<Integer> done) {}

    static Loaded read(Path world) throws IOException {
        try {
            Map<?, ?> states =
                    object(Json.parse(Files.readString(file(world), StandardCharsets.UTF_8)), "journal.json");
            if (!Files.exists(planFile(world))) {
                throw new IOException("journal.json exists but journal-plan.json is missing");
            }
            Map<?, ?> root =
                    object(Json.parse(Files.readString(planFile(world), StandardCharsets.UTF_8)), "journal-plan.json");
            if (number(root.get("format")) != FORMAT || number(states.get("format")) != FORMAT) {
                throw new IOException("the journal has an unknown format");
            }
            String runId = (String) root.get("runId");
            if (!runId.equals(states.get("runId"))) {
                throw new IOException("journal.json and journal-plan.json belong to different runs");
            }
            List<RegionPlan> regions = new ArrayList<>();
            for (Object r : (List<?>) root.get("regions")) {
                Map<?, ?> region = (Map<?, ?>) r;
                List<FilePlan> files = new ArrayList<>();
                for (Object f : (List<?>) region.get("files")) {
                    Map<?, ?> file = (Map<?, ?>) f;
                    List<String> external = new ArrayList<>();
                    for (Object name : (List<?>) file.get("externalFiles")) {
                        external.add((String) name);
                    }
                    files.add(new FilePlan(
                            storage((String) file.get("storage")),
                            indices((String) file.get("remove")),
                            number(file.get("sizeBefore")),
                            number(file.get("modifiedBefore")),
                            number(file.get("sizeAfter")),
                            List.copyOf(external)));
                }
                regions.add(new RegionPlan(
                        (String) region.get("dimension"),
                        (String) region.get("folder"),
                        (String) region.get("file"),
                        List.copyOf(files)));
            }
            List<?> stateList = (List<?>) states.get("regions");
            if (stateList.size() != regions.size()) {
                throw new IOException("journal.json and journal-plan.json list different regions");
            }
            Set<Integer> done = new HashSet<>();
            for (int i = 0; i < stateList.size(); i++) {
                Map<?, ?> state = (Map<?, ?>) stateList.get(i);
                if (!regions.get(i).file().equals(state.get("file"))) {
                    throw new IOException("journal.json and journal-plan.json list different regions");
                }
                if (DONE.equals(state.get("state"))) {
                    done.add(i);
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> report = (Map<String, Object>) root.get("report");
            return new Loaded(
                    new Plan(runId, (String) root.get("backup"), regions, report, (String) root.get("reportText")),
                    done);
        } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException e) {
            throw new IOException("the journal cannot be read: " + e.getMessage(), e);
        }
    }

    /** Deletes the journal, then the plan. */
    static void finish(Path world) throws IOException {
        Files.deleteIfExists(file(world));
        Files.deleteIfExists(planFile(world));
    }

    /** A plan left without its journal: written by a run stopped before its first rewrite. */
    static void deleteStalePlan(Path world) throws IOException {
        if (!exists(world)) {
            Files.deleteIfExists(planFile(world));
        }
    }

    private static String bits(Set<Integer> indices) {
        BitSet bits = new BitSet(1024);
        indices.forEach(bits::set);
        return HexFormat.of().formatHex(java.util.Arrays.copyOf(bits.toByteArray(), 128));
    }

    private static Set<Integer> indices(String hex) {
        BitSet bits = BitSet.valueOf(HexFormat.of().parseHex(hex));
        Set<Integer> result = new HashSet<>();
        bits.stream().forEach(result::add);
        return Set.copyOf(result);
    }

    private static Storage storage(String folder) {
        for (Storage storage : Storage.values()) {
            if (storage.folder().equals(folder)) {
                return storage;
            }
        }
        throw new IllegalArgumentException("unknown storage " + folder);
    }

    private static Map<?, ?> object(Object value, String name) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException(name + " is not a JSON object");
    }

    private static long number(Object value) {
        return ((BigDecimal) value).longValueExact();
    }
}
