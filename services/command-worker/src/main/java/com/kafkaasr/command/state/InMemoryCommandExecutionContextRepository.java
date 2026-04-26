package com.kafkaasr.command.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(
        name = "command.state-store.provider",
        havingValue = "memory",
        matchIfMissing = true)
public class InMemoryCommandExecutionContextRepository implements CommandExecutionContextRepository {

    private final Map<String, CommandExecutionContext> contexts = new ConcurrentHashMap<>();

    @Override
    public CommandExecutionContext findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        return contexts.get(executionId);
    }

    @Override
    public void save(CommandExecutionContext context) {
        if (context == null || context.executionId() == null || context.executionId().isBlank()) {
            throw new IllegalArgumentException("execution context and executionId are required");
        }
        contexts.put(context.executionId(), context);
    }

    @Override
    public void delete(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        contexts.remove(executionId);
    }
}
