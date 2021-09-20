/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.destination.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.commons.functional.CheckedFunction;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.Database;
import io.airbyte.db.Databases;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.base.ssh.SshTunnel;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.standardtest.destination.DestinationAcceptanceTest;
import org.apache.commons.lang3.RandomStringUtils;
import org.jooq.JSONFormat;
import org.jooq.SQLDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstract class that allows us to avoid duplicating testing logic for testing SSH with a key file
 * or with a password.
 */
public abstract class SshMySQLDestinationAcceptanceTest extends DestinationAcceptanceTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(SshMySQLDestinationAcceptanceTest.class);
  private static final JSONFormat JSON_FORMAT = new JSONFormat().recordFormat(JSONFormat.RecordFormat.OBJECT);

  private final ExtendedNameTransformer namingResolver = new ExtendedNameTransformer();
  private String db;

  public abstract Path getConfigFilePath();

  @Override
  protected String getDefaultSchema(JsonNode config) {
    if (config.get("database") == null) {
      return null;
    }
    return config.get("database").asText();
  }

  @Override
  protected String getImageName() {
    return "airbyte/destination-mysql:dev";
  }

  @Override
  protected JsonNode getConfig() {
    var config = getConfigFromSecretsFile();
//    db = RandomStringUtils.randomAlphabetic(8).toLowerCase();
//    ((ObjectNode) config).put("database", db);
    //((ObjectNode) config).put("database", RandomStringUtils.randomAlphabetic(8).toLowerCase());
    return config;
  }

  private JsonNode getConfigFromSecretsFile() {
    return Jsons.deserialize(IOs.readFile(getConfigFilePath()));
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    final JsonNode clone = Jsons.clone(getConfig());
    ((ObjectNode) clone).put("password", "wrong password");
    return clone;
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv env,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
          throws Exception {
    return retrieveRecordsFromTable(namingResolver.getRawTableName(streamName), namespace)
            .stream()
            .map(r -> Jsons.deserialize(r.get(JavaBaseConstants.COLUMN_NAME_DATA).asText()))
            .collect(Collectors.toList());
  }

  @Override
  protected boolean supportsNormalization() {
    return true;
  }

  @Override
  protected boolean supportsDBT() {
    return true;
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv env,
                                                     final String streamName,
                                                     final String namespace)
          throws Exception {
    String tableName = namingResolver.getIdentifier(streamName);
    String schema = namingResolver.getIdentifier(namespace);
    return retrieveRecordsFromTable(tableName, schema);
  }

  @Override
  protected List<String> resolveIdentifier(final String identifier) {
    final List<String> result = new ArrayList<>();
    final String resolved = namingResolver.getIdentifier(identifier);
    result.add(identifier);
    result.add(resolved);
    if (!resolved.startsWith("\"")) {
      result.add(resolved.toLowerCase());
      result.add(resolved.toUpperCase());
    }
    return result;
  }

  private static Database getDatabaseFromConfig(final JsonNode config) {
    var a = Databases.createMySqlDatabase(
            config.get("username").asText(),
            config.get("password").asText(),
            String.format("jdbc:mysql://%s:%s/%s",
                    config.get("host").asText(),
                    config.get("port").asText(),
                    config.get("database").asText()));

    return a;
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, String schemaName) throws Exception {
    LOGGER.error("111111!!!!");
    return SshTunnel.sshWrap(
            getConfig(),
            MySQLDestination.HOST_KEY,
            MySQLDestination.PORT_KEY,
            (CheckedFunction<JsonNode, List<JsonNode>, Exception>) mangledConfig -> getDatabaseFromConfig(mangledConfig)
                    .query(
                            ctx -> ctx
                                    .fetch(String.format("SELECT * FROM %s.%s ORDER BY %s ASC;", mangledConfig.get("database").asText(), tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT))
                                    .stream()
                                    .map(r -> r.formatJSON(JSON_FORMAT))
                                    .map(Jsons::deserialize)
                                    .collect(Collectors.toList())));
  }

  @Override
  protected void setup(final TestDestinationEnv testEnv) throws Exception {
    var a = getConfig();
    var b = getDatabaseFromConfig(a);
    SshTunnel.sshWrap(
            getConfig(),
            MySQLDestination.HOST_KEY,
            MySQLDestination.PORT_KEY,
            mangledConfig -> {
              getDatabaseFromConfig(mangledConfig).query(ctx -> ctx.fetch(String.format("CREATE DATABASE %s;", "asdfgh")));
//              getDatabaseFromConfig(mangledConfig).query(ctx -> ctx.fetch(String.format("SHOW DATABASES LIKE '%%s';", mangledConfig.get("database").asText())));
            });
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) throws Exception {
    SshTunnel.sshWrap(
            getConfig(),
            MySQLDestination.HOST_KEY,
            MySQLDestination.PORT_KEY,
            mangledConfig -> {
              getDatabaseFromConfig(mangledConfig).query(ctx -> ctx.fetch(
                      String.format("DROP DATABASE %s", mangledConfig.get("database").asText())));
            });
//              var result = getDatabaseFromConfig(mangledConfig).query(ctx -> ctx.fetch(
//                      String.format("SELECT CONCAT('DROP TABLE IF EXISTS ', table_name, ';') AS res " +
//                              "FROM information_schema.tables " +
//                              "WHERE table_schema = '%s';", mangledConfig.get("database").asText())));
//              var list = result.getValues("res");
//              for (var elt : list) {
//                var stringElt = elt.toString();
//                LOGGER.error("elt -> {}", stringElt);
//                getDatabaseFromConfig(mangledConfig).query(ctx -> ctx.fetch(stringElt));
//              }
//            });
  }

}
