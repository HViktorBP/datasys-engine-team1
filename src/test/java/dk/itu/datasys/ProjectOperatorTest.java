package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectOperatorTest {
    @Test
    void emitsColumnsInTheRequestedOrder() {
        var child = new TestListOperator(List.of(
                new Object[]{"a", "b", "c"},
                new Object[]{"d", "e", "f"}));
        var project = new ProjectOperator(child, new int[]{2, 0, 1});
        project.open();
        assertArrayEquals(new Object[]{"c", "a", "b"}, project.next());
        assertArrayEquals(new Object[]{"f", "d", "e"}, project.next());
        assertNull(project.next());
        project.close();
    }
}
