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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway 마이그레이션({@code V1__init.sql})과 JPA 엔티티 매핑의 스키마 일치 검사.
 *
 * <p>실제 PostgreSQL 없이도 "엔티티에 컬럼을 추가했는데 마이그레이션을 안 쓴" 드리프트를 잡기 위해,
 * H2가 Hibernate 매핑으로 만든 테이블/컬럼 집합과 마이그레이션 SQL의 테이블/컬럼 집합을 이름 기준으로 비교한다.
 * (타입 비교가 아니라 존재 비교이므로 PostgreSQL 전용 타입 차이로 깨지지 않는다.)
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SchemaParityTest {

    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "AND", "OR", "REFERENCES", "ON", "NOT");

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("모든 엔티티 테이블·컬럼이 V1 마이그레이션에 정의되어 있다")
    void migrationCoversEntityModel() throws IOException, SQLException {
        Map<String, Set<String>> migration = parseMigration();
        Map<String, Set<String>> mapped = readMappedSchema();

        assertThat(migration.keySet())
                .as("V1__init.sql 테이블 목록")
                .containsExactlyInAnyOrderElementsOf(mapped.keySet());

        for (String table : mapped.keySet()) {
            assertThat(migration.get(table))
                    .as("%s 컬럼이 마이그레이션과 엔티티에서 일치해야 한다", table)
                    .containsExactlyInAnyOrderElementsOf(mapped.get(table));
        }
    }

    /** V1__init.sql의 CREATE TABLE 문을 파싱해 테이블별 컬럼 이름 집합을 만든다. */
    private Map<String, Set<String>> parseMigration() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__init.sql")) {
            assertThat(in).as("V1__init.sql이 클래스패스에 있어야 한다").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 라인 주석 제거 (문자열 리터럴에 '--'가 없는 스키마라 단순 제거로 충분)
        String withoutComments = sql.replaceAll("(?m)--.*$", "");

        Map<String, Set<String>> tables = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\)\\s*;",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(withoutComments);

        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            Set<String> columns = new LinkedHashSet<>();
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
            tables.put(table, columns);
        }
        return tables;
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
