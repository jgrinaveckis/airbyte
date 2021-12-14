/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.jdbc.copy.s3;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import alex.mojaki.s3upload.MultiPartOutputStream;
import alex.mojaki.s3upload.StreamTransferManager;
import com.amazonaws.services.s3.AmazonS3Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.destination.jdbc.SqlOperations;
import io.airbyte.integrations.destination.s3.S3DestinationConfig;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.DestinationSyncMode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * IF YOU'RE SEEING WEIRD BEHAVIOR INVOLVING MOCKED OBJECTS: double-check the mockConstruction() call in setup(). You might need to update the methods
 * being mocked.
 * <p>
 * Tests to help define what the legacy S3 stream copier did.
 * <p>
 * Does not verify SQL operations, as they're fairly transparent.
 */
public class LegacyS3StreamCopierTest {

  private static final int PART_SIZE = 5;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private AmazonS3Client s3Client;
  private JdbcDatabase db;
  private SqlOperations sqlOperations;
  private LegacyS3StreamCopier copier;

  private MockedConstruction<StreamTransferManager> streamTransferManagerMockedConstruction;
  private List<ByteArrayOutputStream> outputStreams;

  @BeforeEach
  public void setup() {
    s3Client = mock(AmazonS3Client.class);
    db = mock(JdbcDatabase.class);
    sqlOperations = mock(SqlOperations.class);

    outputStreams = new ArrayList<>();
    // This is basically RETURNS_SELF, except with getMultiPartOutputStreams configured correctly.
    // Other non-void methods (e.g. toString()) will return null.
    streamTransferManagerMockedConstruction = mockConstruction(
        StreamTransferManager.class,
        (mock, context) -> {
          doReturn(mock).when(mock).numUploadThreads(anyInt());
          doReturn(mock).when(mock).queueCapacity(anyInt());
          doReturn(mock).when(mock).partSize(anyLong());

          // We can't write a fake MultiPartOutputStream, because it doesn't have a public constructor.
          // So instead, we'll build a mock that captures its data into a ByteArrayOutputStream.
          final MultiPartOutputStream stream = mock(MultiPartOutputStream.class);
          doReturn(singletonList(stream)).when(mock).getMultiPartOutputStreams();
          final ByteArrayOutputStream capturer = new ByteArrayOutputStream();
          outputStreams.add(capturer);
          doAnswer(invocation -> {
            capturer.write((int) invocation.getArgument(0));
            return null;
          }).when(stream).write(anyInt());
          doAnswer(invocation -> {
            capturer.write(invocation.getArgument(0));
            return null;
          }).when(stream).write(any(byte[].class));
          doAnswer(invocation -> {
            capturer.write(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
          }).when(stream).write(any(byte[].class), anyInt(), anyInt());
        }
    );

    copier = new LegacyS3StreamCopier(
        // In reality, this is normally a UUID - see CopyConsumerFactory#createWriteConfigs
        "fake-staging-folder",
        DestinationSyncMode.OVERWRITE,
        "fake-schema",
        "fake-stream",
        s3Client,
        db,
        new S3DestinationConfig(
            "fake-endpoint",
            "fake-bucket",
            null,
            "fake-region",
            "fake-access-key-id",
            "fake-secret-access-key",
            PART_SIZE,
            null
        ),
        new ExtendedNameTransformer(),
        sqlOperations
    ) {
      @Override
      public void copyS3CsvFileIntoTable(
          final JdbcDatabase database,
          final String s3FileLocation,
          final String schema,
          final String tableName,
          final S3DestinationConfig s3Config) {
        throw new UnsupportedOperationException("not implemented");
      }
    };
  }

  @AfterEach
  public void teardown() {
    streamTransferManagerMockedConstruction.close();
  }

  @Test
  public void createSequentialStagingFiles_when_multipleFilesRequested() {
    // When we call prepareStagingFile() the first time, it should create exactly one upload manager. The next (MAX_PARTS_PER_FILE - 1) invocations
    // should reuse that same upload manager.
    for (var i = 0; i < LegacyS3StreamCopier.MAX_PARTS_PER_FILE; i++) {
      final String file = copier.prepareStagingFile();
      assertEquals("fake-staging-folder/fake-schema/fake-stream_00000", file, "preparing file number " + i);
      final List<StreamTransferManager> firstManagers = streamTransferManagerMockedConstruction.constructed();
      final StreamTransferManager firstManager = firstManagers.get(0);
      verify(firstManager).partSize(PART_SIZE);
      assertEquals(1, firstManagers.size());
    }

    // Now that we've hit the MAX_PARTS_PER_FILE, we should start a new upload
    final String secondFile = copier.prepareStagingFile();
    assertEquals("fake-staging-folder/fake-schema/fake-stream_00001", secondFile);
    final List<StreamTransferManager> secondManagers = streamTransferManagerMockedConstruction.constructed();
    final StreamTransferManager secondManager = secondManagers.get(1);
    verify(secondManager).partSize(PART_SIZE);
    assertEquals(2, secondManagers.size());
  }

  @Test
  public void closesS3Upload_when_stagingUploaderClosedSuccessfully() throws Exception {
    copier.prepareStagingFile();

    copier.closeStagingUploader(false);

    final List<StreamTransferManager> managers = streamTransferManagerMockedConstruction.constructed();
    final StreamTransferManager manager = managers.get(0);
    verify(manager).complete();
  }

  @Test
  public void closesS3Upload_when_stagingUploaderClosedFailingly() throws Exception {
    copier.prepareStagingFile();

    copier.closeStagingUploader(true);

    final List<StreamTransferManager> managers = streamTransferManagerMockedConstruction.constructed();
    final StreamTransferManager manager = managers.get(0);
    verify(manager).abort();
  }

  @Test
  public void deletesStagingFiles() throws Exception {
    final String file = copier.prepareStagingFile();
    doReturn(true).when(s3Client).doesObjectExist("fake-bucket", file);

    copier.removeFileAndDropTmpTable();

    verify(s3Client).deleteObject("fake-bucket", file);
  }

  @Test
  public void writesContentsCorrectly() throws Exception {
    final String file1 = copier.prepareStagingFile();
    for (int i = 0; i < LegacyS3StreamCopier.MAX_PARTS_PER_FILE - 1; i++) {
      copier.prepareStagingFile();
    }
    copier.write(
        UUID.fromString("f6767f7d-ce1e-45cc-92db-2ad3dfdd088e"),
        new AirbyteRecordMessage()
            .withData(OBJECT_MAPPER.readTree("{\"foo\": 73}"))
            .withEmittedAt(1234L),
        file1
    );
    copier.write(
        UUID.fromString("2b95a13f-d54f-4370-a712-1c7bf2716190"),
        new AirbyteRecordMessage()
            .withData(OBJECT_MAPPER.readTree("{\"bar\": 84}"))
            .withEmittedAt(2345L),
        file1
    );

    final String file2 = copier.prepareStagingFile();
    copier.write(
        UUID.fromString("24eba873-de57-4901-9e1e-2393334320fb"),
        new AirbyteRecordMessage()
            .withData(OBJECT_MAPPER.readTree("{\"asd\": 95}"))
            .withEmittedAt(3456L),
        file2
    );

    copier.closeStagingUploader(false);

    // carriage returns are required b/c RFC4180 requires it :(
    assertEquals(
        "f6767f7d-ce1e-45cc-92db-2ad3dfdd088e,\"{\"\"foo\"\":73}\",1969-12-31 16:00:01.234\r\n"
            + "2b95a13f-d54f-4370-a712-1c7bf2716190,\"{\"\"bar\"\":84}\",1969-12-31 16:00:02.345\r\n",
        outputStreams.get(0).toString(StandardCharsets.UTF_8));
    assertEquals("24eba873-de57-4901-9e1e-2393334320fb,\"{\"\"asd\"\":95}\",1969-12-31 16:00:03.456\r\n",
        outputStreams.get(1).toString(StandardCharsets.UTF_8));
  }
}