package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import dk.itu.datasys.CatalogStore.Chunk;
import dk.itu.datasys.CatalogStore.DataFile;
import dk.itu.datasys.CatalogStore.Partition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies how a statement's change is replayed on, or rebased onto, a committed partition list. */
class WriteSetTest {
    /** Creates single-row partition metadata whose only chunk starts at {@code offset}. */
    static Partition partition(long offset) {
        return new Partition(1, List.of(new Chunk(offset, 8, new PartitionStatistics("1", "1"))));
    }

    /** Creates a reference to a one-partition file. */
    static DataFile file(String name) {
        return new DataFile("data/" + name, List.of(partition(8)));
    }

    /** Identifies the only partition of a one-partition file. */
    static WriteSet.PartitionKey key(DataFile file) {
        return new WriteSet.PartitionKey(file.path(), file.partitions().getFirst());
    }

    /** Creates the write set of a delete that replaces one partition. */
    static WriteSet replacing(DataFile original, List<DataFile> replacement) {
        return new WriteSet(Map.of(key(original), replacement), List.of());
    }

    @Test void unchangedBaseKeepsUntouchedFilesAndSubstitutesInPlace() {
        var a = file("a");
        var b = file("b");
        var c = file("c");
        var bNew = file("b-new");
        var result = replacing(b, List.of(bNew)).applyTo(List.of(a, b, c)).orElseThrow();
        assertEquals(List.of(a, bNew, c), result);
        assertSame(a, result.getFirst());
    }

    @Test void removalDropsThePartition() {
        var a = file("a");
        var b = file("b");
        assertEquals(Optional.of(List.of(a)), replacing(b, List.of()).applyTo(List.of(a, b)));
    }

    @Test void appendedFilesFollowEveryCommittedFile() {
        var a = file("a");
        var winner = file("winner");
        var mine = file("mine");
        assertEquals(Optional.of(List.of(a, winner, mine)),
                WriteSet.append(List.of(mine)).applyTo(List.of(a, winner)));
    }

    @Test void replacementRebasesOntoAListThatGrew() {
        var a = file("a");
        var b = file("b");
        var winner = file("winner");
        var aNew = file("a-new");
        assertEquals(Optional.of(List.of(aNew, b, winner)),
                replacing(a, List.of(aNew)).applyTo(List.of(a, b, winner)));
    }

    @Test void partitionAlreadyReplacedByAnotherCommitIsAConflict() {
        var a = file("a");
        var b = file("b");
        var bByWinner = file("b-by-winner");
        assertTrue(replacing(b, List.of()).applyTo(List.of(a, bByWinner)).isEmpty());
    }

    @Test void touchedSharedFileSplitsWhileOtherFilesKeepTheirIdentity() {
        var first = partition(8);
        var second = partition(16);
        var shared = new DataFile("data/shared", List.of(first, second));
        var other = file("other");
        var secondNew = file("second-new");
        Map<WriteSet.PartitionKey, List<DataFile>> replacements = new LinkedHashMap<>();
        replacements.put(new WriteSet.PartitionKey(shared.path(), second), List.of(secondNew));
        var result = new WriteSet(replacements, List.of()).applyTo(List.of(shared, other)).orElseThrow();
        assertEquals(List.of(new DataFile("data/shared", List.of(first)), secondNew, other), result);
        assertSame(other, result.getLast());
    }

    @Test void emptyWriteSetReproducesTheCommittedList() {
        var files = List.of(file("a"), file("b"));
        assertEquals(Optional.of(files), WriteSet.append(List.of()).applyTo(files));
    }
}
