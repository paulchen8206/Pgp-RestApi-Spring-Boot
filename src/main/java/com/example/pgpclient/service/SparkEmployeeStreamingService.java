package com.example.pgpclient.service;

import com.example.pgpclient.config.StreamingProperties;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SparkEmployeeStreamingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SparkEmployeeStreamingService.class);

    private final RemotePgpApiService remotePgpApiService;
    private final StreamingProperties streamingProperties;

    public SparkEmployeeStreamingService(RemotePgpApiService remotePgpApiService,
                                         StreamingProperties streamingProperties) {
        this.remotePgpApiService = remotePgpApiService;
        this.streamingProperties = streamingProperties;
    }

    public void start() throws Exception {
        if (!streamingProperties.enabled()) {
            LOGGER.info("Spark streaming is disabled");
            return;
        }

        validateConfiguration();

        SparkSession spark = SparkSession.builder()
                .appName(streamingProperties.appName())
                .master(streamingProperties.master())
            .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", "1")
                .getOrCreate();

        Dataset<Row> ticks = spark.readStream()
                .format("rate")
                .option("rowsPerSecond", streamingProperties.rowsPerSecond())
                .option("numPartitions", 1)
                .load();

        StreamingQuery query = ticks.writeStream()
                .outputMode("append")
                .option("checkpointLocation", streamingProperties.checkpointLocation())
                .trigger(Trigger.ProcessingTime(streamingProperties.triggerIntervalSeconds() * 1000))
                .foreachBatch(this::processBatch)
                .start();

        LOGGER.info(
                "Started Spark streaming with employee IDs {} and trigger interval {}s",
                streamingProperties.employeeIds(),
                streamingProperties.triggerIntervalSeconds()
        );

        query.awaitTermination();
    }

    private void processBatch(Dataset<Row> batch, long batchId) {
        List<Long> offsets = batch.select("value").as(Encoders.LONG()).collectAsList();
        if (offsets.isEmpty()) {
            LOGGER.debug("Skipping empty micro-batch {}", batchId);
            return;
        }

        List<String> employeeIds = streamingProperties.employeeIds();
        for (Long value : offsets) {
            String employeeId = employeeIds.get((int) Math.floorMod(value, employeeIds.size()));
            try {
                remotePgpApiService.requestData(employeeId);
                LOGGER.info("Processed streaming event for employeeId={} batchId={}", employeeId, batchId);
            } catch (Exception ex) {
                LOGGER.error("Failed to process employeeId={} in batchId={}", employeeId, batchId, ex);
            }
        }
    }

    private void validateConfiguration() {
        if (streamingProperties.employeeIds() == null || streamingProperties.employeeIds().isEmpty()) {
            throw new IllegalStateException("app.streaming.employee-ids must contain at least one employee ID");
        }
        if (streamingProperties.rowsPerSecond() < 1) {
            throw new IllegalStateException("app.streaming.rows-per-second must be greater than 0");
        }
        if (streamingProperties.triggerIntervalSeconds() < 1) {
            throw new IllegalStateException("app.streaming.trigger-interval-seconds must be greater than 0");
        }
    }
}