package com.example.realtimehribicontrol;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.Random;

public class RandomStimuliGeneration {

    private static final int BIT = 8;
    private static final long STIMULI_TIME_1 = 50;
    private static final long STIMULI_TIME_2 = 50;
    private static final int MAX_RANDOM_VALUE = 256;

    private Random random = new Random();
    private Context context;
    int count=0;
    boolean done=false;

    public RandomStimuliGeneration(Context context) {
        this.context = context;
    }

    public RandomResult randomDec2Bin(double nowIBI) {
        int action = random.nextInt(MAX_RANDOM_VALUE);
        int[] stimuli = new int[BIT];

        for (int i = 0; action != 0; i++) {
            stimuli[i] = action % 2;
            action /= 2;
        }

        handleVibration(stimuli, nowIBI);
        if (count==50){
            done=true;
        }else {
            done=false;
            count++;
        }
        return new RandomResult(buildInfoArray(stimuli),done);
    }
    private String buildInfoArray(int[] stimuli) {
        StringBuilder stimuliBuilder = new StringBuilder();
        for (int s : stimuli) {
            stimuliBuilder.append(s);
        }
        String stimuliString = stimuliBuilder.toString();
        return stimuliString;
    }
    public static class RandomResult{
        String stimuli;
        boolean done;

        public RandomResult(String stimuli,boolean done){
            this.stimuli=stimuli;
            this.done=done;
        }
    }

    private void handleVibration(int[] stimuli, double nowIBI) {
        double interval = nowIBI / 4;
        double interval1 = nowIBI / 4 - STIMULI_TIME_1;
        double interval2 = nowIBI / 4 - STIMULI_TIME_2;

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < stimuli.length; i++) {
                long stimuliTime = (i == 1 || i == 4) ? STIMULI_TIME_1 : STIMULI_TIME_2;
                double sleepInterval = (i == 1 || i == 4) ? interval1 : interval2;

                if (stimuli[i] == 1 && vibrator != null) {
                    vibrateDevice(vibrator, stimuliTime);
                    sleepThread(sleepInterval);
                } else if (stimuli[i] == 0) {
                    sleepThread(interval);
                }
            }
        }
    }

    private void vibrateDevice(Vibrator vibrator, long stimuliTime) {

        VibrationEffect vibrationEffect = VibrationEffect.createOneShot(stimuliTime, VibrationEffect.DEFAULT_AMPLITUDE);
        vibrator.vibrate(vibrationEffect);

    }

    private void sleepThread(double interval) {
        try {
            Thread.sleep((long) interval);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
