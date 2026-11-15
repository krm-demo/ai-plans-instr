///usr/bin/env jbang --quiet "$0" "$@" ; exit $?
//JAVA 21+
//REPOS mavencentral,mavenlocal
//DEPS io.github.krm-demo:plans-core:0.1.0-SNAPSHOT
//DEPS info.picocli:picocli:4.7.7
//DEPS org.postgresql:postgresql:42.7.7

import com.example.plans.model.DdaAccInfo;
import com.example.plans.model.InvAccInfo;
import com.example.plans.model.PlanInstructions;
import com.example.plans.model.PlanSearchCriteria;
import com.example.plans.model.PlanStatus;
import com.example.plans.model.PlanType;
import com.example.plans.model.RestInfo;
import com.example.plans.model.RuntimeInfo;
import com.example.plans.model.ThresholdsInfo;
import com.example.plans.persistence.PlanJdbcClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Command line for the plans library: load, save, lookup and search PlanInstructions in Postgres.
 *
 * <pre>
 * jbang PlansCli.java --help
 * jbang plans@krm-demo/ai-plans-instr search --user u1 --status ACTIVE,PAUSED
 * </pre>
 *
 * The connection comes from options or environment variables PLANS_DB_URL, PLANS_DB_USER and
 * PLANS_DB_PASSWORD. Results are printed as JSON on stdout (so they can be piped into jq),
 * messages and errors go to stderr. Exit codes: 0 ok, 1 not found, 2 error.
 */
@Command(name = "plans",
        mixinStandardHelpOptions = true,
        version = "plans-cli 0.1",
        description = "Load, save, lookup and search plan instructions stored in Postgres.",
        subcommands = {
                PlansCli.Load.class, PlansCli.Lookup.class, PlansCli.Search.class,
                PlansCli.Save.class, PlansCli.Template.class, PlansCli.Schema.class})
public class PlansCli implements Runnable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String... args) {
        CommandLine cmd = new CommandLine(new PlansCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                    System.err.println("Error: " + rootMessage(ex));
                    if (System.getenv("PLANS_DEBUG") != null) {
                        ex.printStackTrace();
                    }
                    return 2;
                });
        System.exit(cmd.execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    // ------------------------------------------------------------------------------------------
    // shared options and helpers
    // ------------------------------------------------------------------------------------------

    /** Database connection, shared by all commands that talk to Postgres. */
    static class DbOptions {

        @Option(names = "--db-url", paramLabel = "URL",
                defaultValue = "${env:PLANS_DB_URL:-jdbc:postgresql://localhost:5432/plans}",
                description = "JDBC url (env PLANS_DB_URL). Default: ${DEFAULT-VALUE}")
        String url;

        @Option(names = "--db-user", paramLabel = "USER",
                defaultValue = "${env:PLANS_DB_USER:-plans}",
                description = "database user (env PLANS_DB_USER). Default: ${DEFAULT-VALUE}")
        String user;

        @Option(names = "--db-password", paramLabel = "PASSWORD",
                arity = "0..1", interactive = true,
                defaultValue = "${env:PLANS_DB_PASSWORD:-}",
                showDefaultValue = CommandLine.Help.Visibility.NEVER,
                description = "database password (env PLANS_DB_PASSWORD); give the option without a value to be asked")
        String password;

        PlanJdbcClient client() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
            return new PlanJdbcClient(JdbcClient.create(dataSource), JSON, Clock.systemUTC());
        }
    }

    static String toJson(Object value) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    /** Prints the plan as JSON and returns 0, or reports that nothing was found and returns 1. */
    static int printOrNotFound(Optional<PlanInstructions> plan, String notFoundMessage) {
        if (plan.isPresent()) {
            System.out.println(toJson(plan.get()));
            return 0;
        }
        System.err.println(notFoundMessage);
        return 1;
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == t ? message : t.getClass().getSimpleName() + ": " + message;
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("HOSTNAME", "unknown");
        }
    }

    // ------------------------------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------------------------------

    @Command(name = "load", mixinStandardHelpOptions = true,
            description = "Load one plan by its plan_id (primary key).")
    static class Load implements Callable<Integer> {
        @Mixin DbOptions db;

        @Parameters(index = "0", paramLabel = "PLAN_ID", description = "the plan_id (UUID)")
        UUID planId;

        @Override
        public Integer call() {
            return printOrNotFound(db.client().load(planId), "No plan with id " + planId);
        }
    }

    @Command(name = "lookup", mixinStandardHelpOptions = true,
            description = "Find the latest non-cancelled plan of a user for a plan type.")
    static class Lookup implements Callable<Integer> {
        @Mixin DbOptions db;

        @Option(names = {"-u", "--user"}, required = true, description = "user id")
        String userId;

        @Option(names = {"-t", "--type"}, required = true,
                description = "plan type: ${COMPLETION-CANDIDATES}")
        PlanType planType;

        @Override
        public Integer call() {
            return printOrNotFound(db.client().lookup(userId, planType),
                    "No plan for user " + userId + " and type " + planType);
        }
    }

    @Command(name = "search", mixinStandardHelpOptions = true,
            description = "Search plans. All filters are optional and combined with AND; newest first.")
    static class Search implements Callable<Integer> {
        @Mixin DbOptions db;

        @Option(names = {"-u", "--user"}, description = "user id")
        String userId;

        @Option(names = {"-t", "--type"}, split = ",",
                description = "plan types (comma separated): ${COMPLETION-CANDIDATES}")
        Set<PlanType> types;

        @Option(names = {"-s", "--status"}, split = ",",
                description = "plan statuses (comma separated): ${COMPLETION-CANDIDATES}")
        Set<PlanStatus> statuses;

        @Option(names = "--currency", description = "ISO 4217 code, e.g. USD")
        Currency currency;

        @Option(names = "--dda", paramLabel = "ACC_NUM", description = "DDA account number")
        String ddaAccNum;

        @Option(names = "--inv", paramLabel = "ACC_NUM", description = "investment account number")
        String invAccNum;

        @Option(names = "--from", paramLabel = "DATE_TIME",
                description = "created at or after (UTC), e.g. 2026-11-15T10:00:00")
        LocalDateTime createdFrom;

        @Option(names = "--to", paramLabel = "DATE_TIME",
                description = "created before (UTC), e.g. 2026-11-16T00:00:00")
        LocalDateTime createdTo;

        @Option(names = "--limit", description = "page size (default 50, max 500)")
        Integer limit;

        @Option(names = "--offset", description = "rows to skip (default 0)")
        Integer offset;

        @Override
        public Integer call() {
            List<PlanInstructions> found = db.client().search(new PlanSearchCriteria(
                    userId, types, statuses, currency, ddaAccNum, invAccNum,
                    createdFrom, createdTo, limit, offset));
            System.out.println(toJson(found));
            System.err.println(found.size() + " plan(s) found");
            return 0;
        }
    }

    @Command(name = "save", mixinStandardHelpOptions = true,
            description = {
                    "Create or update a plan from JSON (file or stdin).",
                    "Without a version in the JSON the plan is created (plan_id is generated);",
                    "with a version the plan is updated if the stored version still matches.",
                    "restInfo is filled in here (user agent 'plans-cli', this machine, time)."})
    static class Save implements Callable<Integer> {
        @Mixin DbOptions db;

        @Parameters(index = "0", arity = "0..1", paramLabel = "FILE",
                description = "JSON file; omit or use '-' to read standard input")
        String file;

        @Override
        public Integer call() throws Exception {
            String json = (file == null || file.equals("-"))
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(file));
            PlanInstructions plan = JSON.readValue(json, PlanInstructions.class);

            Instant jvmStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
            RestInfo restInfo = new RestInfo("plans-cli (jbang)", Instant.now(),
                    new RuntimeInfo(hostName(), jvmStart, "cli"));

            PlanInstructions saved = db.client().save(plan.withRestInfo(restInfo));
            System.out.println(toJson(saved));
            System.err.println((plan.version() == null ? "created " : "updated ") + saved.planId()
                    + " (version " + saved.version() + ")");
            return 0;
        }
    }

    @Command(name = "template", mixinStandardHelpOptions = true,
            description = "Print a sample plan as JSON, to edit and pass to 'save'.")
    static class Template implements Callable<Integer> {
        @Option(names = {"-u", "--user"}, defaultValue = "user-1", description = "user id. Default: ${DEFAULT-VALUE}")
        String userId;

        @Override
        public Integer call() {
            PlanInstructions sample = new PlanInstructions(null,
                    PlanType.values()[0], PlanStatus.ACTIVE, userId,
                    new DdaAccInfo("1234567890", "BANK-001"),
                    new InvAccInfo("9876543210", "OCCD-01"),
                    Currency.getInstance("USD"),
                    new ThresholdsInfo(new BigDecimal("10"), new BigDecimal("500"),
                            new BigDecimal("5"), new BigDecimal("100")),
                    null, null, null, null);
            System.out.println(toJson(sample));
            return 0;
        }
    }

    @Command(name = "schema", mixinStandardHelpOptions = true,
            description = "Print the SQL of the database schema that ships with the library.")
    static class Schema implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:plans/db/migration/*.sql");
            Arrays.sort(files, Comparator.comparing(Resource::getFilename));
            for (Resource file : files) {
                System.out.println("-- " + file.getFilename());
                System.out.println(new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            return files.length == 0 ? 1 : 0;
        }
    }
}
