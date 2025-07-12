package com.example.realtimehribicontrol;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IBIControlEnv {

    private final Context context;
    private final int maxNumberOfSteps = 10;
    private int mode;
    private boolean start = true;

    private final int bit = 8;
    private List<int[]> data = new ArrayList<>();
    private List<Integer> dataNumber = new ArrayList<>();
    private List<int[]> actionsData = new ArrayList<>();
    private double beat = bit / 4.0;

    private int[] state = {0, 0, 0};
    private int steps = 0;
    private boolean done = false;

    private double beforeIbi;
    private int counter = 0;

    private Handler vibHandler = new Handler(Looper.getMainLooper());

    public IBIControlEnv(int mode, Context context) {
        if(mode==5||mode==8){
            this.mode = 5;
        }else {
            this.mode=6;
        }
        this.context = context;
        data.add(new int[bit]);
        dataNumber.add(0);
        actionsData.add(new int[4]);
    }

    public void reset() {
        state = new int[]{0, 0, 0};
        steps = 0;
        done = false;

        if (start) {
            beforeIbi = 800;
            start = false;
        }
    }

    public int[] getState() {
        return state.clone();
    }

    public getInfo step(int action1, int action2, int action3, int action4) {
        int[] parameta = dec2bin(action1, action2, action3, action4);
        int[] actions = {action1, action2, action3, action4};
        Result checkResults = checker(parameta, actions);
        int[] stimuli = checkResults.data;
        int checkFlag = checkResults.checkFlag;
        int place = checkResults.place;

        //重複がなければ刺激の呈示
        if (checkFlag == 0) {
            handleStimuliAsync(stimuli);
            counter++;
        }
        return new getInfo(buildInfoArray(stimuli),place,checkFlag,counter);
    }
    public StepResult stepGetResult(double nowIbi, int checkFlag, String stimuli, int place){

        int reward = getReward(nowIbi, checkFlag);
        if (checkFlag==0){
            beforeIbi = nowIbi;
            if (steps == maxNumberOfSteps) {
                done = true;
            } else{
                steps += 1;
            }
        }
        String[] info ={String.valueOf(nowIbi),stimuli, String.valueOf(place), String.valueOf(checkFlag)};
     return new StepResult(state,reward,done,info);
    }

    // 非同期で振動刺激を実行
    private void handleStimuliAsync(int[] stimuli) {
        double interval0 = beforeIbi / 4;
        long stimuliTime1 = 50;
        double interval1 = beforeIbi / 4 - stimuliTime1;
        long stimuliTime2 = 50;
        double interval2 = beforeIbi / 4 - stimuliTime2;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        runVibrationStep(vibrator, stimuli, 0, 0, interval0, stimuliTime1, interval1, stimuliTime2, interval2);
    }

    private void runVibrationStep(Vibrator vibrator, int[] stimuli, int j, int i, double interval0, long stimuliTime1, double interval1, long stimuliTime2, double interval2) {
        if (j >= 5) return;
        if (i >= stimuli.length) {
            vibHandler.postDelayed(() -> runVibrationStep(vibrator, stimuli, j + 1, 0, interval0, stimuliTime1, interval1, stimuliTime2, interval2), 0);
            return;
        }
        long stimuliTime = (i == 1 || i == 4) ? stimuliTime1 : stimuliTime2;
        double sleepInterval = (i == 1 || i == 4) ? interval1 : interval2;

        if (stimuli[i] == 1 && vibrator != null) {
            vibrate(vibrator, stimuliTime);
            vibHandler.postDelayed(() -> runVibrationStep(vibrator, stimuli, j, i + 1, interval0, stimuliTime1, interval1, stimuliTime2, interval2), (long) sleepInterval);
        } else {
            vibHandler.postDelayed(() -> runVibrationStep(vibrator, stimuli, j, i + 1, interval0, stimuliTime1, interval1, stimuliTime2, interval2), (long) interval0);
        }
    }

    private void vibrate(Vibrator vibrator, long duration) {
        VibrationEffect vibrationEffect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
        vibrator.vibrate(vibrationEffect);
    }

    private String buildInfoArray(int[] stimuli) {
        StringBuilder stimuliBuilder = new StringBuilder();
        for (int s : stimuli) {
            stimuliBuilder.append(s);
        }
        String stimuliString = stimuliBuilder.toString();
        return stimuliString;
    }

    public int getReward(double nowIbi, int checkFlag) {
        int reward = 0;
        if (checkFlag == 1) {
            state = new int[]{0, 0, checkFlag};
            reward = -2;
            return reward;
        } else if (nowIbi > beforeIbi) {
            state = new int[]{1, 0, checkFlag};
            if(this.mode==5){reward = 2;}
            else if(this.mode==6){reward = 0;}
            return reward;
        } else {
            state = new int[]{0, 1, checkFlag};
            if(this.mode==5){reward = 0;}
            else if(this.mode==6){reward = 2;}
            return reward;
        }

    }

    private int[] dec2bin(int action1, int action2, int action3, int action4) {
        int[] stimuli = new int[bit];

        int[] temp3 = new int[2];
        int[] temp4 = new int[4];

        for (int i = 0; action3 != 0; i++) {
            temp3[i] = action3 % 2;
            action3 /= 2;
        }

        for (int i = 0; action4 != 0; i++) {
            temp4[i] = action4 % 2;
            action4 /= 2;
        }

        stimuli[0] = action1;
        stimuli[1] = temp4[0];
        stimuli[2] = temp3[0];
        stimuli[3] = temp4[1];
        stimuli[4] = action2;
        stimuli[5] = temp4[2];
        stimuli[6] = temp3[1];
        stimuli[7] = temp4[3];

        return stimuli;
    }

    public Result checker(int[] stimuli, int[] actions) {
        int place = 0;
        int checkFlag1 = 0;
        int checkFlag2 = 0;
        int checkFlag;

        for (int[] actionData : actionsData) {
            if (Arrays.equals(actions, actionData)) {
                checkFlag1 = 1;
                break;
            }
        }

        for (int i = 0; i < data.size(); i++) {
            int[] checkData = Arrays.copyOf(data.get(i), data.get(i).length * 2);
            System.arraycopy(data.get(i), 0, checkData, data.get(i).length, data.get(i).length);
            for (int j = 0; j < bit * 2; j++) {
                if (Arrays.equals(Arrays.copyOfRange(checkData, j, j + bit), stimuli)) {
                    checkFlag2 = 1;
                    place = i;
                    break;
                }
            }
            if (checkFlag2 == 1) {
                break;
            }
        }

        if (checkFlag1 == 0 && checkFlag2 == 0) {
            actionsData.add(actions);
            data.add(stimuli);
            place = data.size() - 1;
            checkFlag = 0;
        } else if (checkFlag1 == 0 && checkFlag2 == 1) {
            checkFlag = 1;
        } else {
            checkFlag = 0;
        }

        dataNumber.add(place);

        return new Result(data.get(place), checkFlag, place);
    }

    public static class Result {
        int[] data;
        int checkFlag;
        int place;

        public Result(int[] data, int checkFlag, int place) {
            this.data = data;
            this.checkFlag = checkFlag;
            this.place = place;
        }
    }

    public static class getInfo{
        String stimuli;
        int place;
        int checkFlag;
        int counter;
        public getInfo(String stimuli,int place,int checkFlag,int counter){
            this.stimuli=stimuli;
            this.place=place;
            this.checkFlag=checkFlag;
            this.counter=counter;
        }
    }

    public static class StepResult {
        int[] state;
        int reward;
        boolean done;
        String[] info;

        public StepResult(int[] state, int reward, boolean done, String[] info) {
            this.state = state;
            this.reward = reward;
            this.done = done;
            this.info = info;
        }
    }
}
