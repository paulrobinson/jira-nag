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

    private static final String JIRA_QUERY_ALL = "project = Quarkus AND (fixVersion is EMPTY or fixVersion = Later.GA) AND status != Closed AND (labels not in ('upstream-kafka') OR labels is empty) AND assignee is not EMPTY";

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
                /*"<p>You have the following issues assigned to you on an already released Red Hat Build of Quarkus release.</p>" +
                "<p>The issues are in the 'To Do' or 'Analysis in Progress' states, despite them being marked with 'fix version/s' that is already released.</p>" +
                "<p>Please review the following issues and: correct the issue status and/or assign to a later release.</p>";*/

                "<p>We're in the process of planning what will be included in the next Red Hat Build of Quarkus feature release (codename: Fireball) and the following feature release (codename: something beginning with 'G').</p>" +
                "<p>A November release date is currently being targeted for Fireball. This means it will likely be based on a Quarkus upstream release from September or maybe October.</p>" +
                "<p>You have the following JIRA issues assigned to you that are NOT currently planned for any RHBQ released. Please can you set the fix version based on the following criteria:</p>" +
                "<ul>" +
                  "<li><b>2.y-Fireball.GA:</b> Issues that can be fixed upstream around September or October 2022. In the case of issues not requiring an upstream code change, you will have until around November 2022.</li>" +
                  "<li><b>x.y-G.GA:</b> Issues that can be fixed for the following RHBQ feature release (merge upstream ~February 2023, or ~March for non-upstream impacting issues)</li>" +
                  "<li><b>Later.GA:</b> Issues that are valid and complete, but can't be fixed in either of the above timeframes</li>" +
                "</ul>" +
                "<p>Thanks for your help with this.</p>";

                //"<p>You have the following issues assigned to you on the upcoming Elektra.GA (AKA 2.7.x) Red Hat Build of Quarkus release.</p>" +
                //"<p>This release is approaching the final stages, so there shouldn't be many issues in the 'To Do' state and other issues should be close to the 'Implemented' state.</p>" +
                //"<p>The time for merging PRs for this release has now passed (except for super-critical security fixes). Therefore, there should not be any JIRA issues in the following states: 'To Do', 'Analysis in Progress', 'Ready for Dev', 'Dev in Progress'.</p>" +
                //"<p>JIRA Issues that won't have their fix merged into Quarkus upstream (and labelled for backport) by the <B>10th December</B> should be moved to the 'Elektra.GA' or 'Later.GA' Fix Version in JIRA. " +
                //"<p>JIRA Issues that won't have their fix <B>merged</B> into Quarkus upstream by the <B>18th January</B> should be moved to the 'Fireball.GA' or 'Later.GA' Fix Version in JIRA. " +
                //"<p>Please update the issues below. Either: move the issue to the 'Implemented' state if the PR is already merged into the main Quarkus branch; or move the issue to the 'Fireball.GA' or 'Later.GA' Fix Version in JIRA if the fix did not make it in.</p>" +
                //"<p>Please mention Thomas Qvarnström and I, in a comment on the issue, if you think deferring it would cause significant impact or if you are unsure of what release to target.";
                //"<b>NOTE:</b> Quarkus 2.2.Final is a hardening release, so only bug fixes and other critical stabilization fixes will be accepted. Other changes to well isolated extensions may also be accepted as long as they don't risk the stability of the release.</p>" +
                //"<p><b>So for the following issues can you:</b> check that the status & assignee is correct and also defer any issues to the 'Elektra.GA' or 'Later.GA' release if they can't make the 10th December deadline.</p>";
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

        /*body += "<p>The states are documented here: <a href='https://docs.google.com/document/d/1s5pzo73HS9QYF1oQjv8ff-ATAK-u8dU_iL3qTe859xA/edit#heading=h.17kii7ptsosf'>Quarkus Product Process | JIRA Workflow</a>.";
        body += "</p>";
        body += "<p>To summarise the states from an engineering perspective...";
        body += "<br><b>To Do:</b> New issues yet to be worked on</br>";
        body += "<br><b>Analysis In Progress:</b> The issue is being discussed/planned/analysed before actual development begins\n";
        body += "<br><b>Dev In Progress:</b> Coding on the issue is in progress (Developer moves the issue here when coding has begun)\n";
        body += "<br><b>Implemented:</b> The PR for the code is merged and out of the Developer's hands (Developer moves issue to here when the PR is merged)</br>";
        body += "<br><b>Resolved:</b> The issue is available in a release (Productization moves issues from implemented to here during release)</br>";*/

        body += "</p>";
        body += "<p>Thanks,</p>";
        body += "<p>Paul (via the <a href='https://github.com/paulrobinson/jira-nag'>JIRA-Nag tool</a>)</p>";

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
