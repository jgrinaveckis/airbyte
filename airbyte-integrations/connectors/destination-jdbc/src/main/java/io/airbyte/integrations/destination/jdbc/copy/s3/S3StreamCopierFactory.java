package io.airbyte.integrations.destination.jdbc.copy.s3;

import com.amazonaws.services.s3.AmazonS3;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.destination.jdbc.SqlOperations;
import io.airbyte.integrations.destination.jdbc.copy.StreamCopier;
import io.airbyte.integrations.destination.jdbc.copy.StreamCopierFactory;
import io.airbyte.integrations.destination.s3.S3DestinationConfig;
import io.airbyte.protocol.models.AirbyteStream;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;

public abstract class S3StreamCopierFactory implements StreamCopierFactory<S3DestinationConfig> {

  /**
   * Used by the copy consumer.
   */
  @Override
  public StreamCopier create(final String configuredSchema,
                             final S3DestinationConfig s3Config,
                             final String stagingFolder,
                             final ConfiguredAirbyteStream configuredStream,
                             final ExtendedNameTransformer nameTransformer,
                             final JdbcDatabase db,
                             final SqlOperations sqlOperations) {
    try {
      final AirbyteStream stream = configuredStream.getStream();
      final String schema = StreamCopierFactory.getSchema(stream.getNamespace(), configuredSchema, nameTransformer);
      final AmazonS3 s3Client = s3Config.getS3Client();

      return create(stagingFolder, schema, s3Client, db, s3Config, nameTransformer, sqlOperations, configuredStream);
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * For specific copier suppliers to implement.
   */
  protected abstract StreamCopier create(String stagingFolder,
                                         String schema,
                                         AmazonS3 s3Client,
                                         JdbcDatabase db,
                                         S3DestinationConfig s3Config,
                                         ExtendedNameTransformer nameTransformer,
                                         SqlOperations sqlOperations,
                                         ConfiguredAirbyteStream configuredStream
  ) throws Exception;
}
