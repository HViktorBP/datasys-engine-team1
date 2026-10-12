# Experiment Design

## Question and x axis

Experiment 1:

Vary maxRowsPerPartition as the 8-row partitions of the current design has been shown to be a performance bottleneck.
Vary between different sizes: 8, 500, 1000, 5000, 10000, 64000

## Metric and y axis

Experiment 1: Measure durationMs on create and copy to mimic the feedback of Exercise 4. It showed 67.23s CREATE + COPY time on 100MB CSV,
so this is clearly a bottleneck.

Experiment 2: Measure durationMs on SELECT \* FROM trips WHERE distance > 900 to mimic the feedback of Exercise 4. Showed 11 second query time on 100MB csv.

Plot:

X-axis: maxRowsPerPartition
Y-axis: Time (durationMs)
Lines: Dataset size (varied)

## Procedure

Generate datasets of different sizes (8K, 1MB, 50MB, 100MB, 500MB)

Repetitions: 5

Cold run: Not very relevant on COPY, but could be relevant on WHERE. Do a warm up run first.

Machine: Some Macbook

JVM: 25

Heap size: 8GB

## Hypothesis

8 rows has been shown to be extremely slow for COPY on 100Mb. Increasing should decrease durationMs.
But it should not be as much of an issue on small datasets - so on small datasets, small partitions should perform better.
