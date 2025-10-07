package com.nakazawa.realtimeibibp.trainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class TinyGptTrainer {

    private final String pythonExe;
    private final String scriptPath;

    public TinyGptTrainer(String pythonExe, String scriptPath) {
        this.pythonExe = pythonExe;   // e.g. "python3"
        this.scriptPath = scriptPath; // e.g. "scripts/train_tinygpt.py"
    }

    /**
     * Python スクリプトを外部プロセスとして起動し、ログを標準出力に流す。
     */
    public void train(String dataDir, String outDir, int epochs, int batchSize) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExe);
        cmd.add(scriptPath);
        cmd.add("--data_dir"); cmd.add(dataDir);
        cmd.add("--out_dir");  cmd.add(outDir);
        cmd.add("--epochs");   cmd.add(String.valueOf(epochs));
        cmd.add("--batch_size"); cmd.add(String.valueOf(batchSize));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println("[TinyGPT] " + line);
            }
        }

        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("TinyGPT training failed with code " + exitCode);
        }
    }
}