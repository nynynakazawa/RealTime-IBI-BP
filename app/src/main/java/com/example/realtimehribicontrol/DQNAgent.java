package com.example.realtimehribicontrol;

import android.util.Log;

import java.util.List;
import java.util.Random;

public class DQNAgent {
    private int actionSize;
    private ExperienceReplayBuffer replayBuffer;
    private Random random;
    private NeuralNetwork qNetwork;
    private NeuralNetwork targetNetwork;
    private double gamma = 0.99;
    private double learningRate = 0.0001;
    private int updateFrequency = 100;
    private int steps = 0;
    private double epsilon = 1.0; // 初期探索率
    private double epsilonMin = 0.01; // 最小探索率
    private double epsilonDecay = 0.9; // 探索率の減衰率

    public DQNAgent(int inputSize, int actionSize) {
        this.actionSize = actionSize;
        this.replayBuffer = new ExperienceReplayBuffer(100);
        this.random = new Random();
        this.qNetwork = new NeuralNetwork(inputSize, actionSize);
        this.targetNetwork = new NeuralNetwork(inputSize, actionSize);
        this.targetNetwork.setWeights(this.qNetwork.getWeights());
    }

    public int selectAction(int[] state) {
        if (random.nextDouble() < epsilon) {
            // ランダムな行動を選択
            return random.nextInt(actionSize);
        } else {
            // Q値が最大の行動を選択
            return maxQAction(state);
        }
    }

    public void storeExperience(int[] state, int action, double reward, int[] nextState, boolean done, double priority) {
        replayBuffer.add(state, action, reward, nextState, done, priority);
    }

    public void train(int batchSize) {
        if (replayBuffer.sample(batchSize).size() < batchSize) {
            return;
        }

        List<ExperienceReplayBuffer.Experience> batch = replayBuffer.sample(batchSize);

        for (ExperienceReplayBuffer.Experience experience : batch) {
            double target = experience.reward;
            if (!experience.done) {
                target += gamma * targetNetwork.predict(experience.nextState, maxQAction(experience.nextState));
            }
            qNetwork.train(experience.state, experience.action, target, learningRate);
        }

        steps++;
        if (steps % updateFrequency == 0) {
            targetNetwork.setWeights(qNetwork.getWeights());
        }

        // 探索率の減衰
        if (epsilon > epsilonMin) {
            epsilon *= epsilonDecay;
        }
    }

    public double getQValue(int[] state, int action) {
        return qNetwork.predict(state, action);
    }

    private int maxQAction(int[] state) {
        double maxQ = Double.NEGATIVE_INFINITY;
        int maxAction = 0;
        for (int a = 0; a < actionSize; a++) {
            double qValue = qNetwork.predict(state, a);
            if (qValue > maxQ) {
                maxQ = qValue;
                maxAction = a;
            }
        }
        return maxAction;
    }
}
