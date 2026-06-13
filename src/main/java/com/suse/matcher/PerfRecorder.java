package com.suse.matcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton accumulator for per-run stage timings and solver internals.
 *
 * Written to a sidecar JSON file when --perf-output is supplied to Main.
 * All fields are set from a single thread (the main matcher thread) except
 * bestScoreArrivalMs which is set from the solver thread via the
 * SolverEventListener; volatile is sufficient.
 */
public class PerfRecorder {

    public static final String INSTRUMENTATION_VERSION = "1.0";

    private static final PerfRecorder INSTANCE = new PerfRecorder();

    private volatile long jvmStartupMs;
    private volatile long kieContainerConstructionMs;
    private volatile long ruleFiringMs;
    private volatile long solverInitMs;
    private volatile long solveTotalMs;
    private volatile long bestScoreArrivalMs = -1L;
    private volatile long messageCollectionMs;
    private volatile long endToEndMs;
    private volatile String terminateReason;
    private volatile long hardScore;
    private volatile long softScore;

    private PerfRecorder() { }

    public static PerfRecorder get() {
        return INSTANCE;
    }

    public void setJvmStartupMs(long ms) { jvmStartupMs = ms; }
    public void setKieContainerConstructionMs(long ms) { kieContainerConstructionMs = ms; }
    public void setRuleFiringMs(long ms) { ruleFiringMs = ms; }
    public void setSolverInitMs(long ms) { solverInitMs = ms; }
    public void setSolveTotalMs(long ms) { solveTotalMs = ms; }
    public void setBestScoreArrivalMs(long ms) { bestScoreArrivalMs = ms; }
    public void setMessageCollectionMs(long ms) { messageCollectionMs = ms; }
    public void setEndToEndMs(long ms) { endToEndMs = ms; }
    public void setTerminateReason(String reason) { terminateReason = reason; }
    public void setHardScore(long score) { hardScore = score; }
    public void setSoftScore(long score) { softScore = score; }

    /**
     * Writes the accumulated data to the given path as JSON.
     * Uses an atomic write (temp file + rename) so that a partial write is
     * never visible; if the JVM is killed before this method is called no
     * sidecar file is produced (pool harness detects the absence and writes
     * an ERROR row to phase3_runs).
     */
    public void writeSidecar(Path outputPath) throws IOException {
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("jvm_startup", jvmStartupMs);
        timing.put("drools_kiecontainer_construction", kieContainerConstructionMs);
        timing.put("drools_rule_firing", ruleFiringMs);
        timing.put("optaplanner_solver_init", solverInitMs);
        timing.put("optaplanner_solve_total", solveTotalMs);
        timing.put("best_score_arrival", bestScoreArrivalMs >= 0L ? bestScoreArrivalMs : null);
        timing.put("message_collection", messageCollectionMs);
        timing.put("end_to_end", endToEndMs);

        Map<String, Object> solver = new LinkedHashMap<>();
        solver.put("terminate_reason", terminateReason);
        solver.put("hard_score", hardScore);
        solver.put("soft_score", softScore);
        solver.put("move_count_explored", null);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("instrumentation_version", INSTRUMENTATION_VERSION);
        root.put("timing_ms", timing);
        root.put("solver", solver);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(root);

        Path tempPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        Files.writeString(tempPath, json);
        Files.move(tempPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
