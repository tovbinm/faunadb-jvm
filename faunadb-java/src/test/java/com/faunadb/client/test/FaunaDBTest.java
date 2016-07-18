package com.faunadb.client.test;

import com.faunadb.client.FaunaClient;
import com.faunadb.client.types.Value;
import com.faunadb.client.types.Value.RefV;
import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.Random;

import static com.faunadb.client.query.Language.*;
import static com.faunadb.client.types.Codec.REF;
import static com.faunadb.client.types.Codec.STRING;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static java.lang.String.format;

public class FaunaDBTest {

  private static final String ROOT_TOKEN;
  private static final String ROOT_URL;

  protected static FaunaClient client;

  static {
    ROOT_TOKEN = EnvVariables.require("FAUNA_ROOT_KEY");
    ROOT_URL = format(
      "%s://%s:%s",
      EnvVariables.getOrElse("FAUNA_SCHEME", "https"),
      EnvVariables.getOrElse("FAUNA_DOMAIN", "rest.faunadb.com"),
      EnvVariables.getOrElse("FAUNA_PORT", "443")
    );
  }

  @BeforeClass
  public static void setUpClient() throws Exception {
    String dbName = format("faunadb-java-test-%s", new Random().nextLong());

    try (FaunaClient rootClient = createFaunaClient(ROOT_TOKEN)) {
      client = transform(
        transformAsync(
          createDatabase(rootClient, dbName),
          createServerKey(rootClient)
        ),
        createClientWithServerKey(rootClient)
      ).get();
    }
  }

  @AfterClass
  public static void closeClients() throws Exception {
    client.close();
  }

  protected static FaunaClient createFaunaClient(String secret) {
    try {
      return FaunaClient.builder()
        .withEndpoint(ROOT_URL)
        .withSecret(secret)
        .build();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static ListenableFuture<Value> createDatabase(FaunaClient rootClient, String dbName) {
    return rootClient.query(
      Create(
        Ref("databases"),
        Obj("name", Value(dbName))
      )
    );
  }

  private static AsyncFunction<Value, Value> createServerKey(final FaunaClient rootClient) {
    return new AsyncFunction<Value, Value>() {
      @Override
      public ListenableFuture<Value> apply(Value dbCreateR) throws Exception {
        RefV dbRef = dbCreateR.at("ref").to(REF).get();

        return rootClient.query(
          Create(
            Ref("keys"),
            Obj("database", dbRef,
              "role", Value("server"))
          )
        );
      }
    };
  }

  private static Function<Value, FaunaClient> createClientWithServerKey(final FaunaClient rootClient) {
    return new Function<Value, FaunaClient>() {
      @Override
      public FaunaClient apply(Value serverKeyF) {
        String secret = serverKeyF.at("secret").to(STRING).get();
        return rootClient.newSessionClient(secret);
      }
    };
  }

}