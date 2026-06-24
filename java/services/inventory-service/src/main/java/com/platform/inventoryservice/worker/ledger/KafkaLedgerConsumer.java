package com.platform.inventoryservice.worker.ledger;

import com.platform.inventoryservice.config.InventoryProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaLedgerConsumer {

    private final ConsumerFactory<String, String> consumerFactory;
    private final InventoryProperties props;
    private final MutationEventProcessor processor;
    private volatile boolean running = true;
    private Consumer<String, String> kafkaConsumer;

    @PostConstruct
    void init() {
        kafkaConsumer = consumerFactory.createConsumer();
        Thread t = new Thread(this::pollLoop, "ledger-worker-" + UUID.randomUUID());
        t.setDaemon(true);
        t.start();
    }

    private void pollLoop() {
        kafkaConsumer.subscribe(List.of(props.getKafka().getTopicEvents()));
        while (running) {
            try {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(
                    Duration.ofMillis(props.getKafka().getPollIntervalMs()));
                if (records.isEmpty()) continue;

                processBatch(records);
                kafkaConsumer.commitSync();
            } catch (WakeupException e) {
                if (!running) {
                    break;
                } else {
                    throw e;
                }
            } catch (Exception e) {
                log.error("Ledger worker poll error, seeking back to retry", e);
                try {
                    for (org.apache.kafka.common.TopicPartition tp : kafkaConsumer.assignment()) {
                        org.apache.kafka.clients.consumer.OffsetAndMetadata committed = kafkaConsumer.committed(tp);
                        if (committed != null) {
                            kafkaConsumer.seek(tp, committed.offset());
                        } else {
                            kafkaConsumer.seekToBeginning(List.of(tp));
                        }
                    }
                    Thread.sleep(1000); // Backoff before retry
                } catch (Exception ex) {
                    log.error("Failed to seek back, restarting consumer", ex);
                    break; // break loop to stop thread. The orchestrator/k8s will restart the pod.
                }
            }
        }
        kafkaConsumer.close();
    }

    private void processBatch(ConsumerRecords<String, String> records) {
        // Group by sku_id for batch processing
        List<ConsumerRecord<String, String>> recordList = new ArrayList<>();
        records.forEach(recordList::add);

        Map<String, List<ConsumerRecord<String, String>>> grouped =
            recordList.stream().collect(Collectors.groupingBy(ConsumerRecord::key));

        for (Map.Entry<String, List<ConsumerRecord<String, String>>> entry : grouped.entrySet()) {
            try {
                processor.processBatch(entry.getValue());
            } catch (Exception e) {
                log.error("Batch processing failed for sku {}", entry.getKey(), e);
                throw e;   // don't commit; retry
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (kafkaConsumer != null) {
            kafkaConsumer.wakeup();
        }
    }
}
