package com.nakazawa.realtimeibibp;

import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

public class NeuralNetwork {
    private MultiLayerNetwork model;
    private int outputSize;
    private int hiddenSize = 16; // 隠れ層のユニット数を増やす

    public NeuralNetwork(int inputSize, int outputSize) {
        this.outputSize = outputSize;
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(new Adam(0.01)) // 学習率を上げる
                .list()
                .layer(0, new DenseLayer.Builder().nIn(inputSize).nOut(hiddenSize).activation(Activation.RELU).build())
                .layer(1, new DenseLayer.Builder().nIn(hiddenSize).nOut(hiddenSize).activation(Activation.RELU).build())
                .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .activation(Activation.IDENTITY)
                        .nIn(hiddenSize).nOut(outputSize).build())
                .build();

        model = new MultiLayerNetwork(conf);
        model.init();
    }

    public double predict(int[] state, int action) {
        double[][] inputArray = new double[1][state.length];
        for (int i = 0; i < state.length; i++) {
            inputArray[0][i] = (double) state[i];
        }
        INDArray input = Nd4j.create(inputArray);
        INDArray output = model.output(input);
        return output.getDouble(0, action);
    }

    public void train(int[] state, int action, double target, double learningRate) {
        double[] stateArray = new double[state.length];
        for (int i = 0; i < state.length; i++) {
            stateArray[i] = (double) state[i];
        }
        INDArray input = Nd4j.create(stateArray, new int[]{1, state.length});
        INDArray label = Nd4j.zeros(1, outputSize);
        action = Math.min(action, outputSize - 1);
        label.putScalar(0, action, target);
        model.fit(input, label);
    }

    public void setWeights(double[] weights) {
        if (weights.length != model.numParams()) {
            throw new IllegalArgumentException("The length of the weights array does not match the number of parameters in the model.");
        }
        model.setParams(Nd4j.create(weights));
    }

    public double[] getWeights() {
        return model.params().toDoubleVector();
    }
}
