package com.kafkaasr.command.state;

public interface CommandExecutionContextRepository {

    CommandExecutionContext findByExecutionId(String executionId);

    void save(CommandExecutionContext context);

    void delete(String executionId);
}
