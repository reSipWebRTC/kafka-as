package com.kafkaasr.command.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.kafkaasr.command.pipeline.CommandExecutionState;
import org.junit.jupiter.api.Test;

class InMemoryCommandExecutionContextRepositoryTests {

    private final InMemoryCommandExecutionContextRepository repository = new InMemoryCommandExecutionContextRepository();

    @Test
    void saveAndFindRoundTrip() {
        CommandExecutionContext context = new CommandExecutionContext(
                "exec-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "trc-1",
                1713744001000L,
                "打开客厅灯",
                "CONTROL",
                "SMART_HOME",
                true,
                1,
                2,
                "cfm-1",
                CommandExecutionState.WAIT_CONFIRM);

        repository.save(context);

        CommandExecutionContext loaded = repository.findByExecutionId("exec-1");
        assertEquals("exec-1", loaded.executionId());
        assertEquals(CommandExecutionState.WAIT_CONFIRM, loaded.state());
        assertEquals(1, loaded.confirmRound());
    }

    @Test
    void deleteRemovesContext() {
        CommandExecutionContext context = new CommandExecutionContext(
                "exec-1",
                "sess-1",
                "tenant-a",
                null,
                "usr-1",
                "trc-1",
                1713744001000L,
                "打开客厅灯",
                "CONTROL",
                "SMART_HOME",
                true,
                1,
                2,
                "cfm-1",
                CommandExecutionState.WAIT_CONFIRM);
        repository.save(context);

        repository.delete("exec-1");

        assertNull(repository.findByExecutionId("exec-1"));
    }
}
