// Copyright 2017 The Promagent Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.promagent.internal.hooks;

import io.promagent.agent.annotations.After;
import io.promagent.agent.annotations.Before;
import io.promagent.agent.annotations.Hook;
import io.promagent.internal.metrics.MetricsUtil;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Summary;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.promagent.internal.hooks.Context.*;

@Hook(instruments = {
        java.sql.Statement.class,
        java.sql.Connection.class
})
public class JdbcHook {

    private static final String SQL_QUERIES_TOTAL = "sql_queries_total";
    private static final String SQL_QUERY_DURATION = "sql_query_duration";

    private final Context context;
    private long startTime = 0;
    private boolean relevant = false;

    public JdbcHook(Context context) {
        this.context = context;
    }

    public static void init(CollectorRegistry registry) {

        // These example metrics are redundant, as the Summary already contains a count.
        // However, I want to show two types of metrics in the example code.

        Counter.build()
                .name(SQL_QUERIES_TOTAL)
                .labelNames("query", "method", "path")
                .help("Total number of sql queries.")
                .register(registry);

        Summary.build()
                .quantile(0.5, 0.05)   // Add 50th percentile (= median) with 5% tolerated error
                .quantile(0.9, 0.01)   // Add 90th percentile with 1% tolerated error
                .quantile(0.99, 0.001) // Add 99th percentile with 0.1% tolerated error
                .name(SQL_QUERY_DURATION)
                .labelNames("query", "method", "path")
                .help("Duration for serving the sql queries in seconds.")
                .register(registry);
    }

    private String stripValues(String query) {
        // We want the structure of the query as labels, not the actual values.
        // Therefore, we replace:
        // insert into Member (id, name, email, phone_number) values (0, 'John Smith', 'john.smith@mailinator.com', '2125551212')
        // with
        // insert into Member (id, name, email, phone_number) values (...)
        return query.replaceAll("values\\s*\\(.*?\\)", "values (...)");
    }

    // --- before

    @Before(method = {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "prepareStatement", "prepareCall"})
    public void before(String sql) {
        if (getRunningQueries().contains(sql)) {
            // This is a nested call, i.e. this Statement or Connection is called from within another Statement or Connection.
            // We only instrument the outer-most call and ignore nested calls.
            // Returning here will leave the variable relevant=false, so the @After method does not do anything.
            return;
        }
        getRunningQueries().add(sql);
        relevant = true;
        startTime = System.nanoTime();
    }

    @Before(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void before(String sql, int autoGeneratedKeys) {
        before(sql);
    }

    @Before(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void before(String sql, int[] columnIndexes) {
        before(sql);
    }

    @Before(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void before(String sql, String[] columnNames) {
        before(sql);
    }

    @Before(method = {"prepareStatement", "prepareCall"})
    public void before(String sql, int resultSetType, int resultSetConcurrency) {
        before(sql);
    }

    @Before(method = {"prepareStatement", "prepareCall"})
    public void before(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
        before(sql);
    }

    // --- after

    @After(method = {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "prepareStatement", "prepareCall"})
    public void after(String sql) throws Exception {
        if (relevant) {
            try {
                double duration = ((double) System.nanoTime() - startTime) / (double) TimeUnit.SECONDS.toNanos(1L);
                String method = context.get(SERVLET_HOOK_METHOD).orElse("no http context");
                String path = context.get(SERVLET_HOOK_PATH).orElse("no http context");
                String query = stripValues(sql);
                MetricsUtil.inc(SQL_QUERIES_TOTAL, query, method, path);
                MetricsUtil.observe(duration, SQL_QUERY_DURATION, query, method, path);
            } finally {
                getRunningQueries().remove(sql);
            }
        }
    }

    @After(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void after(String sql, int autoGeneratedKeys) throws Exception {
        after(sql);
    }

    @After(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void after(String sql, int[] columnIndexes) throws Exception {
        after(sql);
    }

    @After(method = {"execute", "executeUpdate", "executeLargeUpdate", "prepareStatement"})
    public void after(String sql, String[] columnNames) throws Exception {
        after(sql);
    }

    @After(method = {"prepareStatement", "prepareCall"})
    public void after(String sql, int resultSetType, int resultSetConcurrency) throws Exception {
        after(sql);
    }

    @After(method = {"prepareStatement", "prepareCall"})
    public void after(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws Exception {
        after(sql);
    }

    // ---

    private Set<String> getRunningQueries() {
        if (!context.get(JDBC_HOOK_QUERY).isPresent()) {
            context.put(JDBC_HOOK_QUERY, new HashSet<>());
        }
        return context.get(JDBC_HOOK_QUERY).get();
    }
}
