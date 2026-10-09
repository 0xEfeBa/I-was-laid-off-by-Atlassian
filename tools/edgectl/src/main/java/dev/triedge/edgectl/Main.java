package dev.triedge.edgectl;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "edgectl",
        mixinStandardHelpOptions = true,
        version = "edgectl 0.1.0",
        description = "Manage tenants on the tri-edge platform.")
public final class Main implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
