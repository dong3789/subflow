package com.subflow.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway 마이그레이션(V1 + V2)과 JPA 엔티티 매핑의 스키마 일치 검사.
 *
 * <p>실제 PostgreSQL 없이도 "엔티티에 컬럼을 추가했는데 마이그레이션을 안 쓴" 드리프트를 잡기 위해,
 * H2가 Hibernate 매핑으로 만든 테이블/컬럼 집합과 V1·V2 마이그레이션 SQL의 테이블/컬럼 합집합을 비교한다.
 * (타입 비교가 아니라 존재 비교이므로 PostgreSQL 전용 타입 차이로 깨지지 않는다.)
 *
 * <p>2단계에서는 {@code CREATE TABLE}(V2 신규 테이블)과 {@code ALTER TABLE ... ADD COLUMN}(V1 테이블 보강)을
 * 모두 읽어 V1+V2 전체 스키마를 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaParityTest {

    private static final List<String> MIGRATIONS = List.of(
            "/db/migration/V1__init.sql",
            "/db/migration/V2__core_engine_and_audit.sql");

    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "REFERENCES", "INDEX",
            "AND", "OR", "NOT", "ON");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD\\s+(?:COLUMN\\s+)?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("모든 엔티티 테이블·컬럼이 V1+V2 마이그레이션에 정의되어 있다")
    void migrationCoversEntityModel() throws IOException, SQLException {
        Map<String, Set<String>> migration = parseMigrations();
        Map<String, Set<String>> mapped = readMappedSchema();

        assertThat(migration.keySet())
                .as("V1+V2 마이그레이션 테이블 목록")
                .containsExactlyInAnyOrderElementsOf(mapped.keySet());

        for (String table : mapped.keySet()) {
            assertThat(migration.get(table))
                    .as("%s 컬럼이 마이그레이션과 엔티티에서 일치해야 한다", table)
                    .containsExactlyInAnyOrderElementsOf(mapped.get(table));
        }
    }

    /** V1·V2 SQL을 파싱해 테이블별 컬럼 이름 집합을 만든다(CREATE + ALTER ADD COLUMN). */
    private Map<String, Set<String>> parseMigrations() throws IOException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        for (String path : MIGRATIONS) {
            String sql;
            try (InputStream in = getClass().getResourceAsStream(path)) {
                assertThat(in).as("%s 가 클래스패스에 있어야 한다", path).isNotNull();
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String withoutComments = sql.replaceAll("(?m)--.*$", "");
            collectCreateTables(withoutComments, tables);
            collectAlterTableColumns(withoutComments, tables);
        }
        return tables;
    }

    private void collectCreateTables(String sql, Map<String, Set<String>> tables) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            Set<String> columns = tables.computeIfAbsent(table, key -> new LinkedHashSet<>());
            for (String rawLine : matcher.group(2).split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String firstToken = line.split("\\s+")[0].replaceAll("[\",]", "");
                if (CONSTRAINT_KEYWORDS.contains(firstToken.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (firstToken.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    columns.add(firstToken.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private void collectAlterTableColumns(String sql, Map<String, Set<String>> tables) {
        Matcher matcher = ALTER_TABLE.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            String body = matcher.group(2);
            Matcher addMatcher = ADD_COLUMN.matcher(body);
            while (addMatcher.find()) {
                String candidate = addMatcher.group(1);
                if (CONSTRAINT_KEYWORDS.contains(candidate.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                tables.computeIfAbsent(table, key -> new LinkedHashSet<>())
                        .add(candidate.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Hibernate가 H2에 만든 테이블/컬럼 집합. */
    private Map<String, Set<String>> readMappedSchema() throws SQLException {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select table_schema, table_name, column_name from information_schema.columns")) {
            while (rs.next()) {
                String schema = rs.getString(1);
                // 애플리케이션 스키마(public)만 본다. H2의 PostgreSQL 호환 모드는 pg_catalog 테이블을 함께 노출한다.
                if (schema == null || !schema.equalsIgnoreCase("public")) {
                    continue;
                }
                String table = rs.getString(2).toLowerCase(Locale.ROOT);
                tables.computeIfAbsent(table, key -> new LinkedHashSet<>())
                        .add(rs.getString(3).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }
}
