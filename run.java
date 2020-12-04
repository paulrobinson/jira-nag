//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0, com.atlassian.jira:jira-rest-java-client-api:3.0.0, com.atlassian.jira:jira-rest-java-client-core:3.0.0, org.json:json:20200518, com.konghq:unirest-java:3.7.04, com.sun.mail:javax.mail:1.6.2

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

import com.sun.mail.smtp.SMTPTransport;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

@Command(name = "run", mixinStandardHelpOptions = true, version = "run 0.1",
        description = "GitHub to Jira issue replicator")
class run implements Callable<Integer> {

    @CommandLine.Option(names = {"-j", "--jira-server"}, description = "The JIRA server to connect to", required = true)
    private String jiraServerURL;

    @CommandLine.Option(names = {"-u", "--username"}, description = "The username to use when connecting to the JIRA server", required = true)
    private String jiraUsername;

    @CommandLine.Option(names = {"-p", "--password"}, description = "The password to use when connecting to the JIRA server", required = true)
    private String jiraPassword;

    @CommandLine.Option(names = {"-c", "--config"}, description = "The config file to load the query to version mappings from", required = true)
    private String pathToConfigFile;

    private static final String JIRA_PROJECT_CODE = "QUARKUS";
    private static final String JIRA_QUERY = "project = " + JIRA_PROJECT_CODE + " AND assignee = ssitani and status in ('to do', 'Analysis in Progress', 'Dev In Progress')";

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        /*
            Initialise
         */
        //Map<String, String> configuration = loadQueryToVersionMap(pathToConfigFile);
        final JiraRestClient restClient = new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(new URI(jiraServerURL), jiraUsername, jiraPassword);

        System.out.println("Running: " + JIRA_QUERY);
        SearchResult searchResults = restClient.getSearchClient().searchJql(JIRA_QUERY).claim();
        for (Issue issue : searchResults.getIssues()) {
            System.out.println(issue.getSummary());
        }

        sendMail(createEmailBody("Paul", searchResults.getIssues()));

        return 0;
    }

    public String createEmailBody(String name, Iterable<Issue> issues) {

        String body = "<p>Hi " + name + ",</p>" +
                "<p>You have the following issues assigned to you on an upcoming release. Please check the status is correct and update if needed.</p>";

        body += "<table>";
        body += "<tr><th>Issue</th><th>Summary</th><th>Fix Versions</th><th>Status</th></tr>";
        for (Issue issue : issues) {
            System.out.println(issue + "\n\n\n");

            String fixVersions ="";
            for (Version version : issue.getFixVersions()) {
                fixVersions += version.getName() + " ";
            }
            body += "<tr>";
            body +=     "<td><a href='https://issues.redhat.com/browse/" +  issue.getKey() + "'>" + issue.getKey() + "</a></td>";
            body +=     "<td>" + issue.getSummary() + "</td>";
            body +=     "<td>" + fixVersions + "</td>";
            body +=     "<td>" + issue.getStatus().getName() + "</td>";
            body += "</tr>";
        }
        body += "</table>";

        body += "<p>The states are documented here. But to summarise the important states for engineering are...</p>";
        body += "<p>Thanks,</p>";
        body += "<p>Paul</p>";

        return body;
    }

    // for example, smtp.mailgun.org
    private static final String SMTP_SERVER = "smtp.corp.redhat.com";
    private static final String USERNAME = "";
    private static final String PASSWORD = "";

    private static final String EMAIL_FROM = "probinso@redhat.com";
    private static final String EMAIL_TO = "probinso@redhat.com";
    private static final String EMAIL_TO_CC = "";

    private static final String EMAIL_SUBJECT = "Test Send Email via SMTP (HTML)";
    //private static final String EMAIL_TEXT = "<h1>Hello Java Mail \n ABC123</h1>";

    public static void sendMail(String body) {

        Properties prop = System.getProperties();
        prop.put("mail.smtp.auth", "false");

        Session session = Session.getInstance(prop, null);
        Message msg = new MimeMessage(session);

        try {

            msg.setFrom(new InternetAddress(EMAIL_FROM));

            msg.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(EMAIL_TO, false));

            msg.setSubject(EMAIL_SUBJECT);

            // TEXT email
            //msg.setText(EMAIL_TEXT);

            // HTML email
            msg.setDataHandler(new DataHandler(new HTMLDataSource(body)));


            SMTPTransport t = (SMTPTransport) session.getTransport("smtp");

            // connect
            t.connect(SMTP_SERVER, USERNAME, PASSWORD);

            // send
            t.sendMessage(msg, msg.getAllRecipients());

            System.out.println("Response: " + t.getLastServerResponse());

            t.close();

        } catch (MessagingException e) {
            e.printStackTrace();
        }

    }

    static class HTMLDataSource implements DataSource {

        private String html;

        public HTMLDataSource(String htmlString) {
            html = htmlString;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (html == null) throw new IOException("html message is null!");
            return new ByteArrayInputStream(html.getBytes());
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("This DataHandler cannot write HTML");
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public String getName() {
            return "HTMLDataSource";
        }
    }

    private Map<String, String> loadQueryToVersionMap(String pathToConfigFile) {
        try {

            String jsonString = new String(Files.readAllBytes(Paths.get(pathToConfigFile)));
            JSONArray jsonArray = new JSONArray(jsonString);

            Map<String, String> queryToVersionMap = new HashMap<>();
            for (int i=0; i<jsonArray.length(); i++) {
                JSONObject configItemJson = jsonArray.getJSONObject(i);
                String query = configItemJson.getString("query");
                String version = configItemJson.getString("version");
                queryToVersionMap.put(query, version);
            }

            return queryToVersionMap;
        } catch (IOException e) {
            throw new RuntimeException("Error loading " + pathToConfigFile);
        }
    }

}
