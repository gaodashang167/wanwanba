package ua.nanit.limbo.server.commands;

import ua.nanit.limbo.server.Command;
import ua.nanit.limbo.server.LimboServer;

public class CmdStop implements Command {

    private final LimboServer server;

    public CmdStop(LimboServer server) {
        this.server = server;
    }

    @Override
    public void execute() {
        server.stop();
        System.exit(0);
    }

    @Override
    public String description() {
        return "Stop the server";
    }
}
