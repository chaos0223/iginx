package cn.edu.tsinghua.iginx.filesystem.exec;

import static cn.edu.tsinghua.iginx.engine.logical.utils.ExprUtils.getKeyRangesFromFilter;
import static cn.edu.tsinghua.iginx.filesystem.shared.Constant.SEPARATOR;
import static cn.edu.tsinghua.iginx.filesystem.shared.Constant.WILDCARD;

import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalException;
import cn.edu.tsinghua.iginx.engine.physical.exception.PhysicalTaskExecuteFailureException;
import cn.edu.tsinghua.iginx.engine.physical.memory.execute.stream.EmptyRowStream;
import cn.edu.tsinghua.iginx.engine.physical.storage.domain.Column;
import cn.edu.tsinghua.iginx.engine.physical.task.TaskExecuteResult;
import cn.edu.tsinghua.iginx.engine.shared.KeyRange;
import cn.edu.tsinghua.iginx.engine.shared.data.read.RowStream;
import cn.edu.tsinghua.iginx.engine.shared.data.write.BitmapView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.ColumnDataView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.DataView;
import cn.edu.tsinghua.iginx.engine.shared.data.write.RowDataView;
import cn.edu.tsinghua.iginx.engine.shared.operator.filter.Filter;
import cn.edu.tsinghua.iginx.engine.shared.operator.tag.TagFilter;
import cn.edu.tsinghua.iginx.filesystem.file.entity.FileMeta;
import cn.edu.tsinghua.iginx.filesystem.query.entity.FileSystemHistoryQueryRowStream;
import cn.edu.tsinghua.iginx.filesystem.query.entity.FileSystemQueryRowStream;
import cn.edu.tsinghua.iginx.filesystem.query.entity.FileSystemResultTable;
import cn.edu.tsinghua.iginx.filesystem.query.entity.Record;
import cn.edu.tsinghua.iginx.filesystem.shared.Constant;
import cn.edu.tsinghua.iginx.filesystem.tools.FilePathUtils;
import cn.edu.tsinghua.iginx.metadata.entity.ColumnsInterval;
import cn.edu.tsinghua.iginx.metadata.entity.KeyInterval;
import cn.edu.tsinghua.iginx.thrift.DataType;
import cn.edu.tsinghua.iginx.utils.Pair;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalExecutor implements Executor {

  private static final Logger logger = LoggerFactory.getLogger(LocalExecutor.class);

  private String root;

  private String dummyRoot;

  private boolean hasData;

  private FileSystemManager fileSystemManager;

  public LocalExecutor(boolean isReadOnly, boolean hasData, Map<String, String> extraParams) {
    String dir = extraParams.get(Constant.INIT_INFO_DIR);
    String dummyDir = extraParams.get(Constant.INIT_INFO_DUMMY_DIR);
    try {
      if (hasData) {
        if (dummyDir == null || dummyDir.isEmpty()) {
          throw new IllegalArgumentException("No dummy_dir declared with params " + extraParams);
        }
        File dummyFile = new File(dummyDir);
        if (dummyFile.isFile()) {
          throw new IllegalArgumentException(String.format("invalid dummy_dir %s", dummyDir));
        }
        this.dummyRoot = dummyFile.getCanonicalPath() + SEPARATOR;
        if (!isReadOnly) {
          if (dir == null || dir.isEmpty()) {
            throw new IllegalArgumentException("No dir declared with params " + extraParams);
          }
          File file = new File(dir);
          if (file.isFile()) {
            throw new IllegalArgumentException(String.format("invalid dir %s", dir));
          }
          this.root = file.getCanonicalPath() + SEPARATOR;
          try {
            String dummyDirPath = dummyFile.getCanonicalPath();
            String dirPath = file.getCanonicalPath();
            if (dummyDirPath.equals(dirPath)) {
              throw new IllegalArgumentException(
                  String.format("dir %s cannot be equal to dummy directory %s", dir, dummyDir));
            }
          } catch (IOException e) {
            throw new RuntimeException(
                String.format("get canonical path failed for dir %s dummy_dir %s", dir, dummyDir));
          }
        }
      } else {
        if (dir == null || dir.isEmpty()) {
          throw new IllegalArgumentException("No dir declared with params " + extraParams);
        }
        File file = new File(dir);
        if (file.isFile()) {
          throw new IllegalArgumentException(String.format("invalid dir %s", dir));
        }
        this.root = file.getCanonicalPath() + SEPARATOR;
      }
    } catch (IOException e) {
      logger.error("get dir or dummy dir failure: {}", e.getMessage());
    }
    this.hasData = hasData;
    this.fileSystemManager = new FileSystemManager(extraParams);
  }

  @Override
  public TaskExecuteResult executeProjectTask(
      List<String> paths,
      TagFilter tagFilter,
      Filter filter,
      String storageUnit,
      boolean isDummyStorageUnit) {
    if (isDummyStorageUnit) {
      if (tagFilter != null) {
        logger.error("dummy storage query should not contain tag filter");
        return new TaskExecuteResult(new EmptyRowStream());
      }
      return executeDummyProjectTask(paths, filter);
    }
    return executeQueryTask(storageUnit, paths, tagFilter, filter);
  }

  public TaskExecuteResult executeQueryTask(
      String storageUnit, List<String> paths, TagFilter tagFilter, Filter filter) {
    try {
      List<FileSystemResultTable> result = new ArrayList<>();
      logger.info("[Query] execute query file: {}", paths);
      List<KeyRange> keyRanges = getKeyRangesFromFilter(filter);
      for (String path : paths) {
        result.addAll(
            fileSystemManager.readFile(
                new File(FilePathUtils.toIginxPath(root, storageUnit, path)),
                tagFilter,
                keyRanges,
                false));
      }
      RowStream rowStream = new FileSystemQueryRowStream(result, storageUnit, root, filter);
      return new TaskExecuteResult(rowStream);
    } catch (Exception e) {
      logger.error(
          "read file error, storageUnit {}, paths({}), tagFilter({}), filter({})",
          storageUnit,
          paths,
          tagFilter,
          filter);
      return new TaskExecuteResult(
          new PhysicalTaskExecuteFailureException("execute project task in fileSystem failure", e));
    }
  }

  public TaskExecuteResult executeDummyProjectTask(List<String> paths, Filter filter) {
    try {
      List<FileSystemResultTable> result = new ArrayList<>();
      logger.info("[Query] execute dummy query file: {}", paths);
      List<KeyRange> keyRanges = getKeyRangesFromFilter(filter);
      for (String path : paths) {
        result.addAll(
            fileSystemManager.readFile(
                new File(FilePathUtils.toNormalFilePath(dummyRoot, path)), null, keyRanges, true));
      }
      RowStream rowStream =
          new FileSystemHistoryQueryRowStream(
              result, dummyRoot, filter, fileSystemManager.getMemoryPool());
      return new TaskExecuteResult(rowStream);
    } catch (Exception e) {
      logger.error("read file error, paths {} filter {}", paths, filter);
      return new TaskExecuteResult(
          new PhysicalTaskExecuteFailureException(
              "execute dummy project task in fileSystem failure", e));
    }
  }

  @Override
  public TaskExecuteResult executeInsertTask(DataView dataView, String storageUnit) {
    Exception e = null;
    switch (dataView.getRawDataType()) {
      case Row:
      case NonAlignedRow:
        e = insertRowRecords((RowDataView) dataView, storageUnit);
        break;
      case Column:
      case NonAlignedColumn:
        e = insertColumnRecords((ColumnDataView) dataView, storageUnit);
        break;
    }
    if (e != null) {
      return new TaskExecuteResult(
          null, new PhysicalException("execute insert task in fileSystem failure", e));
    }
    return new TaskExecuteResult(null, null);
  }

  private Exception insertRowRecords(RowDataView data, String storageUnit) {
    List<List<Record>> recordsList = new ArrayList<>();
    List<File> fileList = new ArrayList<>();
    List<Map<String, String>> tagsList = new ArrayList<>();

    for (int j = 0; j < data.getPathNum(); j++) {
      fileList.add(new File(FilePathUtils.toIginxPath(root, storageUnit, data.getPath(j))));
      tagsList.add(data.getTags(j));
    }

    for (int j = 0; j < data.getPathNum(); j++) {
      recordsList.add(new ArrayList<>());
    }

    for (int i = 0; i < data.getKeySize(); i++) {
      BitmapView bitmapView = data.getBitmapView(i);
      int index = 0;
      for (int j = 0; j < data.getPathNum(); j++) {
        if (bitmapView.get(j)) {
          DataType dataType = data.getDataType(j);
          recordsList.get(j).add(new Record(data.getKey(i), dataType, data.getValue(i, index)));
          index++;
        }
      }
    }
    try {
      logger.info("begin to write data");
      return fileSystemManager.writeFiles(fileList, recordsList, tagsList);
    } catch (Exception e) {
      logger.error("encounter error when inserting row records to fileSystem: {}", e.getMessage());
      return e;
    }
  }

  private Exception insertColumnRecords(ColumnDataView data, String storageUnit) {
    List<List<Record>> recordsList = new ArrayList<>();
    List<File> fileList = new ArrayList<>();
    List<Map<String, String>> tagsList = new ArrayList<>();

    for (int j = 0; j < data.getPathNum(); j++) {
      fileList.add(new File(FilePathUtils.toIginxPath(root, storageUnit, data.getPath(j))));
      tagsList.add(data.getTags(j));
    }

    for (int i = 0; i < data.getPathNum(); i++) {
      List<Record> records = new ArrayList<>();
      BitmapView bitmapView = data.getBitmapView(i);
      DataType dataType = data.getDataType(i);
      int index = 0;
      for (int j = 0; j < data.getKeySize(); j++) {
        if (bitmapView.get(j)) {
          records.add(new Record(data.getKey(j), dataType, data.getValue(i, index)));
          index++;
        }
      }
      recordsList.add(records);
    }

    try {
      logger.info("begin to write data");
      return fileSystemManager.writeFiles(fileList, recordsList, tagsList);
    } catch (Exception e) {
      logger.error(
          "encounter error when inserting column records to fileSystem: {}", e.getMessage());
      return e;
    }
  }

  @Override
  public TaskExecuteResult executeDeleteTask(
      List<String> paths, List<KeyRange> keyRanges, TagFilter tagFilter, String storageUnit) {
    Exception exception = null;
    List<File> fileList = new ArrayList<>();
    if (keyRanges == null || keyRanges.isEmpty()) {
      if (paths.size() == 1 && paths.get(0).equals(WILDCARD) && tagFilter == null) {
        try {
          exception =
              fileSystemManager.deleteFile(
                  new File(FilePathUtils.toIginxPath(root, storageUnit, null)));
        } catch (Exception e) {
          logger.error("encounter error when clearing data: {}", e.getMessage());
          exception = e;
        }
      } else {
        for (String path : paths) {
          fileList.add(new File(FilePathUtils.toIginxPath(root, storageUnit, path)));
        }
        try {
          exception = fileSystemManager.deleteFiles(fileList, tagFilter);
        } catch (Exception e) {
          logger.error("encounter error when clearing data: {}", e.getMessage());
          exception = e;
        }
      }
    } else {
      try {
        if (!paths.isEmpty()) {
          for (String path : paths) {
            fileList.add(new File(FilePathUtils.toIginxPath(root, storageUnit, path)));
          }
          for (KeyRange keyRange : keyRanges) {
            exception =
                fileSystemManager.trimFilesContent(
                    fileList, tagFilter, keyRange.getActualBeginKey(), keyRange.getActualEndKey());
          }
        }
      } catch (IOException e) {
        logger.error("encounter error when deleting data: {}", e.getMessage());
        exception = e;
      }
    }
    return new TaskExecuteResult(null, exception != null ? new PhysicalException(exception) : null);
  }

  @Override
  public List<Column> getColumnsOfStorageUnit(String storageUnit) throws PhysicalException {
    List<Column> columns = new ArrayList<>();
    if (root != null) {
      File directory = new File(FilePathUtils.toIginxPath(root, storageUnit, null));
      for (File file : fileSystemManager.getAllFiles(directory, false)) {
        FileMeta meta = fileSystemManager.getFileMeta(file);
        if (meta == null) {
          throw new PhysicalException(
              String.format(
                  "encounter error when getting columns of storage unit because file meta %s is null",
                  file.getAbsolutePath()));
        }
        columns.add(
            new Column(
                FilePathUtils.convertAbsolutePathToPath(root, file.getAbsolutePath(), storageUnit),
                meta.getDataType(),
                meta.getTags(),
                false));
      }
    }
    if (hasData && dummyRoot != null) {
      for (File file : fileSystemManager.getAllFiles(new File(dummyRoot), true)) {
        columns.add(
            new Column(
                FilePathUtils.convertAbsolutePathToPath(
                    dummyRoot, file.getAbsolutePath(), storageUnit),
                DataType.BINARY,
                null,
                true));
      }
    }
    return columns;
  }

  @Override
  public Pair<ColumnsInterval, KeyInterval> getBoundaryOfStorage(String dataPrefix)
      throws PhysicalException {
    KeyInterval keyInterval = new KeyInterval(0, Long.MAX_VALUE);
    ColumnsInterval columnsInterval;

    File directory = new File(FilePathUtils.toNormalFilePath(dummyRoot, dataPrefix));
    if (dataPrefix != null && !dataPrefix.isEmpty()) {
      columnsInterval = new ColumnsInterval(dataPrefix);
    } else {
      Pair<String, String> filePair = fileSystemManager.getBoundaryOfFiles(directory);
      String tmpDummyRoot = dummyRoot.substring(0, dummyRoot.lastIndexOf(SEPARATOR));
      String schemaPrefix = tmpDummyRoot.substring(tmpDummyRoot.lastIndexOf(SEPARATOR) + 1);
      if (filePair == null) {
        columnsInterval = new ColumnsInterval(null, null, schemaPrefix);
      } else {
        columnsInterval =
            new ColumnsInterval(
                FilePathUtils.convertAbsolutePathToPath(dummyRoot, filePair.k, null),
                FilePathUtils.convertAbsolutePathToPath(dummyRoot, filePair.v, null),
                schemaPrefix);
      }
    }

    return new Pair<>(columnsInterval, keyInterval);
  }

  @Override
  public void close() {
    fileSystemManager.close();
  }
}