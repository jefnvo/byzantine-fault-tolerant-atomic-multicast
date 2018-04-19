package ch.usi.inf.dslab.bftamcast.util;

import org.apache.commons.cli.*;

public class CLIParser {
    private Option  id, group,
            globalPercent, clientCount, duration, msgSize, nonGenuine, treeConfig, outstanding;
    private Options options;
    private CommandLineParser parser;
    private CommandLine line;
    private String command;


    private CLIParser() {
        id = Option.builder("i").desc("server id").argName("id").hasArg().numberOfArgs(1).type(Integer.class).build();
        group = Option.builder("g").desc("group id").argName("id").hasArg().numberOfArgs(1).type(Integer.class).build();
        globalPercent = Option.builder("p").desc("percentage of global messages (defaults to 0)").argName("percentage").hasArg().numberOfArgs(1).type(Integer.class).build();
        duration = Option.builder("d").desc("time to execute in seconds (defaults to 120)").argName("seconds").hasArg().numberOfArgs(1).type(Integer.class).build();
        msgSize = Option.builder("s").desc("message size in bytes (defaults to 64)").argName("bytes").hasArg().numberOfArgs(1).type(Integer.class).build();
        clientCount = Option.builder("c").desc("number of client threads (defaults to 1)").argName("clients").hasArg().numberOfArgs(1).type(Integer.class).build();
        treeConfig= Option.builder("t").desc("tree conf file").argName("file").hasArg().numberOfArgs(1).type(String.class).required().build();
        nonGenuine = Option.builder("ng").desc("sets the server to the non-genuine config.uration").hasArg(false).build();
        outstanding = Option.builder("o").desc("number of outstanding messages allowed per client/replica (defaults to 1)").hasArg().numberOfArgs(1).type(Integer.class).build();
        options = new Options();
        parser = new DefaultParser();
        line = null;
    }

    public static CLIParser getClientParser(String[] args) {
        CLIParser parser = new CLIParser();
        parser.command = "Client";
        parser.id.setRequired(true);
        parser.group.setRequired(true);
        parser.options.addOption(parser.id);
        parser.options.addOption(parser.group);
        parser.options.addOption(parser.clientCount);
        parser.options.addOption(parser.duration);
        parser.options.addOption(parser.msgSize);
        parser.options.addOption(parser.globalPercent);
        parser.options.addOption(parser.nonGenuine);
        parser.options.addOption(parser.treeConfig);
        parser.options.addOption(parser.outstanding);
        parser.parse(args);
        return parser;
    }

   
    
    public static CLIParser getServerParser(String[] args) {
        CLIParser parser = new CLIParser();
        parser.command = "Univers server";
        parser.id.setRequired(true);
        parser.group.setRequired(true);
        parser.options.addOption(parser.id);
        parser.options.addOption(parser.group);
        parser.options.addOption(parser.treeConfig);
        parser.options.addOption(parser.outstanding);
        parser.parse(args);
        return parser;
    }


    public int getId() {
        return Integer.parseInt(line.getOptionValue("i"));
    }
    


    public int getGroup() {
        return Integer.parseInt(line.getOptionValue("g"));
    }

    public int getGlobalPercent() {
        String v = line.getOptionValue("p");
        return v == null ? 0 : Integer.parseInt(v);
    }

    public int getClientCount() {
        String v = line.getOptionValue("c");
        return v == null ? 1 : Integer.parseInt(v);
    }

    public int getDuration() {
        String v = line.getOptionValue("d");
        return v == null ? 120 : Integer.parseInt(v);
    }
    
    public int getOutstandingMsg() {
        String v = line.getOptionValue("o");
        return v == null ? 1 : Integer.parseInt(v);
    }

    public int getMsgSize() {
        String v = line.getOptionValue("s");
        return v == null ? 64 : Integer.parseInt(v);
    }
    
    public String getTreeConfig() {
        return line.getOptionValue("t");
    }

    public boolean isNonGenuine() {
        return line.hasOption("ng");
    }

    private void parse(String args[]) {
        try {
            // parse the command line arguments
            line = parser.parse(options, args, false);
        } catch (ParseException exp) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(command, options);
            System.err.println("Fail to parse command line options.  Reason: " + exp.getMessage());
            System.exit(-1);
        }
    }
}
