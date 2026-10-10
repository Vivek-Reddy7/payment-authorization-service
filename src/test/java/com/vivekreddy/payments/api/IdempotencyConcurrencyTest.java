package com.vivekreddy.payments.api;

import com.vivekreddy.payments.repository.AuthorizationRepository;
import com.vivekreddy.payments.repository.IdempotencyRecordRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the idempotency guarantee under real concurrency, not a
 * single-threaded replay.
 *
 * <p>Before the fix in {@code IdempotencyClaimer}, firing this same workload
 * at the service produced multiple authorizations and orphaned rows no
 * idempotency record pointed at. See docs/project-analysis.md for the
 * reproduction and the diagnosis.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

    private static final int CONCURRENCY = 16;
    private static final String PAN = "5555555555554444";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationRepository authorizations;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    private static String body(long amountMinor, String reference) {
        return """
               {
                 "amountMinor": %d,
                 "currency": "USD",
                 "pan": "%s",
                 "expiry": "2030-12",
                 "merchantReference": "%s"
               }
               """.formatted(amountMinor, PAN, reference);
    }

    @Test
    @DisplayName("16 concurrent identical requests produce exactly one authorization")
    void concurrentIdenticalRequestsProduceOneAuthorization() throws Exception {
        String key = "concurrency-identical";
        String reference = "order-concurrency-identical";
        String json = body(12_345L, reference);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            CountDownLatch ready = new CountDownLatch(CONCURRENCY);
            CountDownLatch go = new CountDownLatch(1);

            List<Callable<MvcResult>> tasks = java.util.stream.IntStream.range(0, CONCURRENCY)
                    .<Callable<MvcResult>>mapToObj(i -> () -> {
                        ready.countDown();
                        go.await();
                        return mockMvc.perform(post("/api/v1/authorizations")
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json))
                                .andReturn();
                    })
                    .toList();

            List<Future<MvcResult>> futures = tasks.stream().map(pool::submit).toList();
            ready.await();
            go.countDown();

            Set<Integer> statuses = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                statuses.add(result.getResponse().getStatus());
                String body = result.getResponse().getContentAsString();
                String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                ids.add(id);
            }

            assertThat(statuses).as("every response status").containsExactly(201);
            assertThat(ids).as("every response carries the same authorization id").hasSize(1);
        } finally {
            pool.shutdown();
        }

        assertThat(idempotencyRecords.findAll().stream()
                .filter(r -> r.getIdempotencyKey().equals(key))
                .count())
                .as("idempotency records stored for this key")
                .isEqualTo(1);

        assertThat(authorizations.findAll().stream()
                .filter(a -> a.getMerchantReference().equals(reference))
                .count())
                .as("authorizations created for this merchant reference -- no orphans")
                .isEqualTo(1);
    }
}
