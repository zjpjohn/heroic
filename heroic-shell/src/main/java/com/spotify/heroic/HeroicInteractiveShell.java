package com.spotify.heroic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jline.console.ConsoleReader;
import jline.console.UserInterruptException;
import jline.console.completer.StringsCompleter;
import jline.console.history.FileHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableList;
import com.spotify.heroic.shell.CommandDefinition;
import com.spotify.heroic.shell.CoreShellInterface;
import com.spotify.heroic.shell.QuoteParser;
import com.spotify.heroic.shell.ShellInterface;
import com.spotify.heroic.shell.Tasks;

import eu.toolchain.async.AsyncFuture;

@Slf4j
@RequiredArgsConstructor
public class HeroicInteractiveShell implements ShellInterface {
    final ConsoleReader reader;
    final List<CommandDefinition> commands;
    final FileHistory history;

    boolean running = true;

    // mutable state, a.k.a. settings
    int timeout = 10;

    public void run(final CoreShellInterface core) throws Exception {
        final PrintWriter out = new PrintWriter(reader.getOutput());

        while (running) {
            final String line;

            try {
                line = reader.readLine();
            } catch (UserInterruptException e) {
                out.println("Interrupted");
                break;
            }

            if (line == null) {
                break;
            }

            final List<String> command;

            try {
                command = QuoteParser.parse(line);
            } catch (Exception e) {
                log.error("Line syntax invalid", e);
                continue;
            }

            if (command.isEmpty()) {
                continue;
            }

            final String commandName = command.iterator().next();

            if ("exit".equals(commandName)) {
                running = false;
                continue;
            }

            if ("help".equals(commandName)) {
                printTasksHelp(out);
                continue;
            }

            if ("clear".equals(commandName)) {
                reader.clearScreen();
                continue;
            }

            if ("timeout".equals(commandName)) {
                internalTimeoutTask(out, command);
                continue;
            }

            final long start = System.nanoTime();
            runTask(command, out, core);
            final long diff = System.nanoTime() - start;

            out.println(String.format("time: %s", Tasks.formatTimeNanos(diff)));
        }

        out.println();
        out.println("Exiting...");
    }

    public void shutdown() throws IOException {
        if (history != null) {
            history.flush();
        }

        reader.shutdown();
    }

    void printTasksHelp(PrintWriter out) {
        out.println("Available commands:");

        for (final CommandDefinition c : commands) {
            out.println(String.format("%s - %s", c.getName(), c.getUsage()));

            if (!c.getAliases().isEmpty()) {
                out.println(String.format("  aliases: %s", StringUtils.join(", ", c.getAliases())));
            }
        }
    }

    void internalTimeoutTask(PrintWriter out, List<String> args) {
        if (args.size() < 2) {
            if (this.timeout == 0) {
                out.println("timeout disabled");
            } else {
                out.println(String.format("timeout = %d", this.timeout));
            }

            return;
        }

        final int timeout;

        try {
            timeout = Integer.parseInt(args.get(1));
        } catch (Exception e) {
            out.println(String.format("not a valid integer value: %s", args.get(1)));
            return;
        }

        if (timeout <= 0) {
            out.println("Timeout disabled");
            this.timeout = 0;
        } else {
            out.println(String.format("Timeout updated to %d seconds", timeout));
            this.timeout = timeout;
        }
    }

    void runTask(List<String> command, final PrintWriter out, final CoreShellInterface core) throws Exception,
            IOException {
        final AsyncFuture<Void> t;

        try {
            t = core.command(command, out);
        } catch (Exception e) {
            out.println("Command failed");
            e.printStackTrace(out);
            return;
        }

        if (t == null) {
            out.flush();
            return;
        }

        try {
            awaitFinished(t);
        } catch (TimeoutException e) {
            out.println(String.format("Command timed out (current timeout = %ds)", timeout));
            t.cancel(true);
        } catch (Exception e) {
            out.println("Command failed");
            e.printStackTrace(out);
            return;
        }

        out.flush();
        return;
    }

    Void awaitFinished(final AsyncFuture<Void> t) throws InterruptedException, ExecutionException, TimeoutException {
        if (timeout > 0) {
            return t.get(timeout, TimeUnit.SECONDS);
        }

        log.warn(String.format("Waiting forever for task (timeout = %d)", timeout));
        return t.get();
    }

    public static HeroicInteractiveShell buildInstance(final List<CommandDefinition> commands, FileInputStream input) throws Exception {
        final ConsoleReader reader = new ConsoleReader("heroicsh", input, System.out, null);

        final FileHistory history = setupHistory(reader);

        if (history != null) {
            reader.setHistory(history);
        }

        reader.setPrompt(String.format("heroic> "));
        reader.addCompleter(new StringsCompleter(ImmutableList.copyOf(commands.stream().map((d) -> d.getName())
                .iterator())));
        reader.setHandleUserInterrupt(true);

        return new HeroicInteractiveShell(reader, commands, history);
    }

    private static FileHistory setupHistory(final ConsoleReader reader) throws IOException {
        final String home = System.getProperty("user.home");

        if (home == null) {
            return null;
        }

        return new FileHistory(new File(home, ".heroicsh-history"));
    }
}