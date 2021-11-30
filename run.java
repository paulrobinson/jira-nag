//usr/bin/env jbang "$0" "$@" ; exit $?
//REPOS mavencentral,atlassian=https://packages.atlassian.com/maven/repository/public
//DEPS info.picocli:picocli:4.2.0, com.atlassian.jira:jira-rest-java-client-app:5.2.2, com.atlassian.jira:jira-rest-java-client-api:5.2.2, com.atlassian.jira:jira-rest-java-client-core:5.2.2, org.json:json:20200518, com.konghq:unirest-java:3.7.04, com.sun.mail:javax.mail:1.6.2

import com.atlassian.httpclient.api.Request;
import com.atlassian.jira.rest.client.api.AuthenticationHandler;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.User;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

@Command(name = "run", mixinStandardHelpOptions = true, version = "run 0.1",
        description = "GitHub to Jira issue replicator")
class run implements Callable<Integer> {

    @CommandLine.Option(names = {"-s", "--jira-server"}, description = "The JIRA server to connect to", required = true)
    private String jiraServerURL;

    @CommandLine.Option(names = {"-t", "--jira-token"}, description = "The Personal Access Token for authenticating with the JIRA server", required = true)
    private String jiraToken;

    private static final String SMTP_SERVER = "smtp.corp.redhat.com";
    private static final String USERNAME = "";
    private static final String PASSWORD = "";
    private static final String EMAIL_FROM = "probinso@redhat.com";
    private static final String EMAIL_SUBJECT = "Please review these Quarkus JIRA issues";

    private static final String JIRA_QUERY_ALL = "project = QUARKUS AND status in (\"To Do\", \"Dev In Progress\", \"Ready for Dev\", \"Analysis in Progress\") AND fixVersion = Elektra.GA";
    //private static final String JIRA_QUERY_ALL = "project = QUARKUS AND status in (\"To Do\", \"Dev In Progress\", \"Ready for Dev\", \"Analysis in Progress\") AND fixVersion = Elektra.GA";
    //private static final String JIRA_QUERY_ALL = "project = QUARKUS AND status in (\"To Do\", \"Dev In Progress\", \"Ready for Dev\", \"Analysis in Progress\") AND fixVersion = Dragonball.GA";
    //private static final String JIRA_QUERY_ALL = "project = QUARKUS AND status in (\"to do\") AND fixVersion = Cannonball.GA";
    //private static final String JIRA_QUERY_ALL = "project = QUARKUS AND status in (\"to do\", \"Analysis in Progress\", \"Dev In Progress\") AND fixVersion is not EMPTY AND fixVersion != later";

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        /*
            Initialise
         */
        final JiraRestClient restClient = new AsynchronousJiraRestClientFactory().create(new URI(jiraServerURL), new BearerHttpAuthenticationHandler(jiraToken));

        /*
            Find users with issues
         */
        Set<User> users = getUsersWithIssues(restClient);

        /*
            Email each user with their list of issues
         */
        for (User user : users) {

            String jiraQueryPerUser = JIRA_QUERY_ALL + " AND assignee = '" + user.getName() + "'";
            System.out.println("Running: " + jiraQueryPerUser);
            SearchResult searchResultsPerUser = restClient.getSearchClient().searchJql(jiraQueryPerUser).claim();
            sendMail(createEmailBody(user, searchResultsPerUser.getIssues()), "probinso@redhat.com");
            //sendMail(createEmailBody(user, searchResultsPerUser.getIssues()), user.getEmailAddress());
        }

        return 0;
    }

    private Set<User> getUsersWithIssues(JiraRestClient restClient) {
        System.out.println("Running: " + JIRA_QUERY_ALL);
        SearchResult searchResultsAll = restClient.getSearchClient().searchJql(JIRA_QUERY_ALL).claim();
        Set<User> users = new HashSet<>();
        for (Issue issue : searchResultsAll.getIssues()) {
            if (issue.getAssignee() != null) {
                User user = restClient.getUserClient().getUser(issue.getAssignee().getName()).claim();
                users.add(user);
            }
        }
        return users;
    }

    public String createEmailBody(User user, Iterable<Issue> issues) {

        System.out.println("Sending email for user: " + user.getDisplayName());

        String body = "<p>Hi " + user.getDisplayName() + ",</p>" +
                "<p>You have the following issues assigned to you on the upcoming Red Hat Build of Quarkus release.</p>" +
                //"<p>This release is approaching the final stages, so there shouldn't be many issues in the 'To Do' state.</p>" +
                "<p>JIRA Issues that won't have their fix merged into Quarkus upstream TODAY (ready for the Quarkus 2.2.CR1 release) should be moved to the 'Later.GA' Fix Version in JIRA." +
                "Please mention Thomas Qvarnström and I, in a comment on the issue, if you think deferring it would cause significant impact. " +
                //"<b>NOTE:</b> Quarkus 2.2.Final is a hardening release, so only bug fixes and other critical stabilization fixes will be accepted. Other changes to well isolated extensions may also be accepted as long as they don't risk the stability of the release.</p>" +
                "<p>So for the following issues can you: check that the status & assignee is correct and also defer any issues to the Later.GA release if they can't make it into Quarkus Upstream 2.2.Final.</p>";
                //"<p>The following issues are assigned to you and in the 'To Do' state. Please check that the status is correct and update if needed. Please also check that you are the correct assignee.</p>";

        body += "<table border='1' style='border-collapse:collapse'>";
        body += "<tr><th>Issue</th><th>Summary</th><th>Fix Versions</th><th>Status</th></tr>";
        for (Issue issue : issues) {
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

        body += "<p>The states are documented here: <a href='https://docs.google.com/document/d/1s5pzo73HS9QYF1oQjv8ff-ATAK-u8dU_iL3qTe859xA/edit#heading=h.17kii7ptsosf'>Quarkus Product Process | JIRA Workflow</a>.";
        body += "<p>To summarise the states from an engineering perspective...";
        body += "</p>";
        body += "<br><b>To Do:</b> New issues yet to be worked on</br>";
        body += "<br><b>Analysis In Progress:</b> The issue is being discussed/planned/analysed before actual development begins\n";
        body += "<br><b>Dev In Progress:</b> Coding on the issue is in progress (Developer moves the issue here when coding has begun)\n";
        body += "<br><b>Implemented:</b> The PR for the code is merged and out of the Developer's hands (Developer moves issue to here when the PR is merged)</br>";
        body += "<br><b>Resolved:</b> The issue is available in a release (Productization moves issues from implemented to here during release)</br>";
        body += "</p>";
        body += "<p>Thanks,</p>";
        body += "<p>Paul</p>";

        return body;
    }

    public static void sendMail(String body, String to) {

        Properties prop = System.getProperties();
        prop.put("mail.smtp.auth", "false");

        Session session = Session.getInstance(prop, null);
        Message msg = new MimeMessage(session);

        try {

            msg.setFrom(new InternetAddress(EMAIL_FROM));

            msg.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(to, false));

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

    public static class BearerHttpAuthenticationHandler implements AuthenticationHandler {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private final String token;

        public BearerHttpAuthenticationHandler(final String token) {
            this.token = token;
        }

        @Override
        public void configure(Request.Builder builder) {
            builder.setHeader(AUTHORIZATION_HEADER, "Bearer " + token);
        }
    }
}
