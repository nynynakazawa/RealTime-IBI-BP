package com.nakazawa.realtimeibibp;

import java.util.Random;

public class NeuralNetwork {
    private final int inputSize;
    private final int hiddenSize = 16;
    private final int outputSize;
    private final double[][] w1;
    private final double[] b1;
    private final double[][] w2;
    private final double[] b2;
    private final double[][] w3;
    private final double[] b3;

    public NeuralNetwork(int inputSize, int outputSize) {
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        this.w1 = new double[hiddenSize][inputSize];
        this.b1 = new double[hiddenSize];
        this.w2 = new double[hiddenSize][hiddenSize];
        this.b2 = new double[hiddenSize];
        this.w3 = new double[outputSize][hiddenSize];
        this.b3 = new double[outputSize];
        initWeights();
    }

    private void initWeights() {
        Random random = new Random(123);
        initLayer(random, w1, inputSize);
        initLayer(random, w2, hiddenSize);
        initLayer(random, w3, hiddenSize);
    }

    private void initLayer(Random random, double[][] weights, int fanIn) {
        double scale = Math.sqrt(2.0 / Math.max(1, fanIn));
        for (int i = 0; i < weights.length; i++) {
            for (int j = 0; j < weights[i].length; j++) {
                weights[i][j] = random.nextGaussian() * scale;
            }
        }
    }

    public double predict(int[] state, int action) {
        double[] output = forward(toDoubleArray(state)).output;
        int safeAction = Math.max(0, Math.min(action, outputSize - 1));
        return output[safeAction];
    }

    public void train(int[] state, int action, double target, double learningRate) {
        ForwardPass pass = forward(toDoubleArray(state));
        int safeAction = Math.max(0, Math.min(action, outputSize - 1));

        double[] deltaOut = new double[outputSize];
        deltaOut[safeAction] = pass.output[safeAction] - target;

        double[] delta2 = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double grad = 0.0;
            for (int o = 0; o < outputSize; o++) {
                grad += w3[o][i] * deltaOut[o];
            }
            delta2[i] = pass.z2[i] > 0.0 ? grad : 0.0;
        }

        double[] delta1 = new double[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            double grad = 0.0;
            for (int h = 0; h < hiddenSize; h++) {
                grad += w2[h][i] * delta2[h];
            }
            delta1[i] = pass.z1[i] > 0.0 ? grad : 0.0;
        }

        for (int o = 0; o < outputSize; o++) {
            for (int h = 0; h < hiddenSize; h++) {
                w3[o][h] -= learningRate * deltaOut[o] * pass.a2[h];
            }
            b3[o] -= learningRate * deltaOut[o];
        }

        for (int h = 0; h < hiddenSize; h++) {
            for (int j = 0; j < hiddenSize; j++) {
                w2[h][j] -= learningRate * delta2[h] * pass.a1[j];
            }
            b2[h] -= learningRate * delta2[h];
        }

        for (int h = 0; h < hiddenSize; h++) {
            for (int j = 0; j < inputSize; j++) {
                w1[h][j] -= learningRate * delta1[h] * pass.input[j];
            }
            b1[h] -= learningRate * delta1[h];
        }
    }

    public void setWeights(double[] weights) {
        if (weights.length != numParams()) {
            throw new IllegalArgumentException("The length of the weights array does not match the number of parameters in the model.");
        }
        int idx = 0;
        idx = copyIntoMatrix(weights, idx, w1);
        idx = copyIntoVector(weights, idx, b1);
        idx = copyIntoMatrix(weights, idx, w2);
        idx = copyIntoVector(weights, idx, b2);
        idx = copyIntoMatrix(weights, idx, w3);
        copyIntoVector(weights, idx, b3);
    }

    public double[] getWeights() {
        double[] flat = new double[numParams()];
        int idx = 0;
        idx = copyFromMatrix(flat, idx, w1);
        idx = copyFromVector(flat, idx, b1);
        idx = copyFromMatrix(flat, idx, w2);
        idx = copyFromVector(flat, idx, b2);
        idx = copyFromMatrix(flat, idx, w3);
        copyFromVector(flat, idx, b3);
        return flat;
    }

    private int numParams() {
        return hiddenSize * inputSize + hiddenSize
                + hiddenSize * hiddenSize + hiddenSize
                + outputSize * hiddenSize + outputSize;
    }

    private double[] toDoubleArray(int[] state) {
        double[] input = new double[state.length];
        for (int i = 0; i < state.length; i++) {
            input[i] = state[i];
        }
        return input;
    }

    private ForwardPass forward(double[] input) {
        double[] z1 = affine(w1, b1, input);
        double[] a1 = relu(z1);
        double[] z2 = affine(w2, b2, a1);
        double[] a2 = relu(z2);
        double[] output = affine(w3, b3, a2);
        return new ForwardPass(input, z1, a1, z2, a2, output);
    }

    private double[] affine(double[][] weights, double[] bias, double[] input) {
        double[] out = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            double sum = bias[i];
            for (int j = 0; j < weights[i].length; j++) {
                sum += weights[i][j] * input[j];
            }
            out[i] = sum;
        }
        return out;
    }

    private double[] relu(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.max(0.0, values[i]);
        }
        return out;
    }

    private int copyIntoMatrix(double[] src, int idx, double[][] matrix) {
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                matrix[i][j] = src[idx++];
            }
        }
        return idx;
    }

    private int copyIntoVector(double[] src, int idx, double[] vector) {
        for (int i = 0; i < vector.length; i++) {
            vector[i] = src[idx++];
        }
        return idx;
    }

    private int copyFromMatrix(double[] dst, int idx, double[][] matrix) {
        for (double[] row : matrix) {
            for (double value : row) {
                dst[idx++] = value;
            }
        }
        return idx;
    }

    private int copyFromVector(double[] dst, int idx, double[] vector) {
        for (double value : vector) {
            dst[idx++] = value;
        }
        return idx;
    }

    private static class ForwardPass {
        final double[] input;
        final double[] z1;
        final double[] a1;
        final double[] z2;
        final double[] a2;
        final double[] output;

        ForwardPass(double[] input, double[] z1, double[] a1, double[] z2, double[] a2, double[] output) {
            this.input = input;
            this.z1 = z1;
            this.a1 = a1;
            this.z2 = z2;
            this.a2 = a2;
            this.output = output;
        }
    }
}
