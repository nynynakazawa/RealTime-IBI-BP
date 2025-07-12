package com.example.realtimehribicontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ExperienceReplayBuffer {
    private List<Experience> buffer;
    private int maxSize;
    private Random random;

    public ExperienceReplayBuffer(int maxSize) {
        this.buffer = new ArrayList<>();
        this.maxSize = maxSize;
        this.random = new Random();
    }

    public void add(int[] state, int action, double reward, int[] nextState, boolean done, double priority) {
        if (buffer.size() >= maxSize) {
            buffer.remove(0);
        }
        buffer.add(new Experience(state, action, reward, nextState, done, priority));
    }

    public List<Experience> sample(int batchSize) {
        List<Experience> sample = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            sample.add(buffer.get(random.nextInt(buffer.size())));
        }
        return sample;
    }

    public static class Experience {
        public int[] state;
        public int action;
        public double reward;
        public int[] nextState;
        public boolean done;
        public double priority;

        public Experience(int[] state, int action, double reward, int[] nextState, boolean done, double priority) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.nextState = nextState;
            this.done = done;
            this.priority = priority;
        }
    }
}
