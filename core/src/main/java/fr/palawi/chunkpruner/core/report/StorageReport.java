package fr.palawi.chunkpruner.core.report;

/** Region files of one storage before and after the run. In a dry run, "after" is the estimate. */
public record StorageReport(long files, long bytesBefore, long bytesAfter, long filesChanged, long filesDeleted) {
    public static final StorageReport EMPTY = new StorageReport(0, 0, 0, 0, 0);

    public StorageReport plus(StorageReport other) {
        return new StorageReport(
                files + other.files,
                bytesBefore + other.bytesBefore,
                bytesAfter + other.bytesAfter,
                filesChanged + other.filesChanged,
                filesDeleted + other.filesDeleted);
    }
}
