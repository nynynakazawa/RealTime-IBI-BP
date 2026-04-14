package com.nakazawa.realtimeibibp.bp;

public final class MapPpPrediction {
    public final String[] labels;
    public final double[] mapCoefficients;
    public final double[] ppCoefficients;
    public final double[] sbpCoefficients;
    public final double[] dbpCoefficients;
    public final double[] mapTerms;
    public final double[] ppTerms;
    public final double[] sbpTerms;
    public final double[] dbpTerms;
    public final double mapModelRaw;
    public final double ppModelRaw;
    public final double sbpModelRaw;
    public final double dbpModelRaw;
    public final double sbpFinal;
    public final double dbpFinal;

    public MapPpPrediction(
            String[] labels,
            double[] mapCoefficients,
            double[] ppCoefficients,
            double[] sbpCoefficients,
            double[] dbpCoefficients,
            double[] mapTerms,
            double[] ppTerms,
            double[] sbpTerms,
            double[] dbpTerms,
            double mapModelRaw,
            double ppModelRaw,
            double sbpModelRaw,
            double dbpModelRaw,
            double sbpFinal,
            double dbpFinal) {
        this.labels = labels;
        this.mapCoefficients = mapCoefficients;
        this.ppCoefficients = ppCoefficients;
        this.sbpCoefficients = sbpCoefficients;
        this.dbpCoefficients = dbpCoefficients;
        this.mapTerms = mapTerms;
        this.ppTerms = ppTerms;
        this.sbpTerms = sbpTerms;
        this.dbpTerms = dbpTerms;
        this.mapModelRaw = mapModelRaw;
        this.ppModelRaw = ppModelRaw;
        this.sbpModelRaw = sbpModelRaw;
        this.dbpModelRaw = dbpModelRaw;
        this.sbpFinal = sbpFinal;
        this.dbpFinal = dbpFinal;
    }
}
