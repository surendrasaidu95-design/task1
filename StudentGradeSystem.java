import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class StudentGradeSystem {

    // =========================
    // CONFIGURATION
    // =========================

    private static final int PORT = 8090;
    private static final int PASS_MARK = 35;
    private static final String DATA_FILE = "student_grade_data.dat";

    private static final LinkedHashMap<String, Subject> subjects = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Student> students = new LinkedHashMap<>();

    private static int subjectCounter = 1;
    private static int studentCounter = 1;

    // =========================
    // MAIN
    // =========================

    public static void main(String[] args) throws Exception {

        loadData();

        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0
        );

        server.createContext("/", StudentGradeSystem::handleHome);
        server.createContext("/api/subjects", StudentGradeSystem::handleSubjects);
        server.createContext("/api/students", StudentGradeSystem::handleStudents);
        server.createContext("/api/dashboard", StudentGradeSystem::handleDashboard);
        server.createContext("/api/export", StudentGradeSystem::handleExport);

        server.setExecutor(
                Executors.newCachedThreadPool()
        );

        server.start();

        String url = "http://localhost:" + PORT;

        System.out.println();
        System.out.println("==============================================");
        System.out.println("   STUDENT GRADE MANAGEMENT SYSTEM");
        System.out.println("==============================================");
        System.out.println("Server running at: " + url);
        System.out.println("Press Ctrl+C to stop.");
        System.out.println("==============================================");
        System.out.println();

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(
                        URI.create(url)
                );
            }
        } catch (Exception ignored) {
        }
    }

    // =========================
    // MODELS
    // =========================

    static class Subject implements Serializable {

        String code;
        String name;
        int maxMarks;

        Subject(String code, String name, int maxMarks) {
            this.code = code;
            this.name = name;
            this.maxMarks = maxMarks;
        }
    }

    static class Student implements Serializable {

        String id;
        String name;

        LinkedHashMap<String, Integer> marks =
                new LinkedHashMap<>();

        Student(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static class DataStore implements Serializable {

        LinkedHashMap<String, Subject> subjects;
        LinkedHashMap<String, Student> students;
        int subjectCounter;
        int studentCounter;

        DataStore(
                LinkedHashMap<String, Subject> subjects,
                LinkedHashMap<String, Student> students,
                int subjectCounter,
                int studentCounter
        ) {
            this.subjects = subjects;
            this.students = students;
            this.subjectCounter = subjectCounter;
            this.studentCounter = studentCounter;
        }
    }

    // =========================
    // HOME
    // =========================

    private static void handleHome(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        sendHtml(exchange, buildHtml());
    }

    // =========================
    // SUBJECT API
    // =========================

    private static void handleSubjects(HttpExchange exchange)
            throws IOException {

        String method = exchange.getRequestMethod();

        if (method.equalsIgnoreCase("GET")) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;

            for (Subject s : subjects.values()) {

                if (!first) {
                    json.append(",");
                }

                json.append("{")
                        .append("\"code\":\"")
                        .append(jsonEscape(s.code))
                        .append("\",")
                        .append("\"name\":\"")
                        .append(jsonEscape(s.name))
                        .append("\",")
                        .append("\"maxMarks\":")
                        .append(s.maxMarks)
                        .append("}");

                first = false;
            }

            json.append("]");

            sendJson(exchange, json.toString());
            return;
        }

        if (method.equalsIgnoreCase("POST")) {

            Map<String, String> form =
                    parseForm(exchange);

            String action = form.getOrDefault(
                    "action", "add"
            );

            if (action.equals("add")) {

                String name =
                        form.getOrDefault("name", "").trim();

                String maxText =
                        form.getOrDefault("maxMarks", "").trim();

                if (name.isEmpty()) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Subject name is required.\"}");
                    return;
                }

                int maxMarks;

                try {
                    maxMarks = Integer.parseInt(maxText);
                } catch (Exception e) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Maximum marks must be a valid number.\"}");
                    return;
                }

                if (maxMarks < PASS_MARK) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Maximum marks must be at least 35.\"}");
                    return;
                }

                String code =
                        "SUB" + subjectCounter++;

                Subject subject =
                        new Subject(code, name, maxMarks);

                subjects.put(code, subject);

                saveData();

                sendJson(exchange,
                        "{\"success\":true,\"message\":\"Subject added successfully.\"}");

                return;
            }

            if (action.equals("edit")) {

                String code =
                        form.getOrDefault("code", "");

                String name =
                        form.getOrDefault("name", "").trim();

                String maxText =
                        form.getOrDefault("maxMarks", "").trim();

                Subject subject =
                        subjects.get(code);

                if (subject == null) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Subject not found.\"}");
                    return;
                }

                if (name.isEmpty()) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Subject name is required.\"}");
                    return;
                }

                int maxMarks;

                try {
                    maxMarks =
                            Integer.parseInt(maxText);
                } catch (Exception e) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Invalid maximum marks.\"}");
                    return;
                }

                if (maxMarks < PASS_MARK) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Maximum marks must be at least 35.\"}");
                    return;
                }

                // Do not allow max marks to become lower
                // than already-entered student marks.
                for (Student student : students.values()) {

                    Integer mark =
                            student.marks.get(code);

                    if (mark != null && mark > maxMarks) {

                        sendJson(exchange,
                                "{\"success\":false,\"message\":\"Cannot reduce maximum marks below an existing student mark.\"}");

                        return;
                    }
                }

                subject.name = name;
                subject.maxMarks = maxMarks;

                saveData();

                sendJson(exchange,
                        "{\"success\":true,\"message\":\"Subject updated successfully.\"}");

                return;
            }
        }

        if (method.equalsIgnoreCase("DELETE")) {

            Map<String, String> query =
                    parseQuery(exchange.getRequestURI().getRawQuery());

            String code =
                    query.getOrDefault("code", "");

            if (!subjects.containsKey(code)) {
                sendJson(exchange,
                        "{\"success\":false,\"message\":\"Subject not found.\"}");
                return;
            }

            // Remove this subject from all students.
            for (Student student : students.values()) {
                student.marks.remove(code);
            }

            subjects.remove(code);

            saveData();

            sendJson(exchange,
                    "{\"success\":true,\"message\":\"Subject deleted successfully.\"}");

            return;
        }

        sendText(exchange, 405, "Method Not Allowed");
    }

    // =========================
    // STUDENT API
    // =========================

    private static void handleStudents(HttpExchange exchange)
            throws IOException {

        String method = exchange.getRequestMethod();

        if (method.equalsIgnoreCase("GET")) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;

            for (Student student : students.values()) {

                if (!first) {
                    json.append(",");
                }

                json.append(studentToJson(student));

                first = false;
            }

            json.append("]");

            sendJson(exchange, json.toString());
            return;
        }

        if (method.equalsIgnoreCase("POST")) {

            Map<String, String> form =
                    parseForm(exchange);

            String action =
                    form.getOrDefault("action", "add");

            String name =
                    form.getOrDefault("name", "").trim();

            if (name.isEmpty()) {
                sendJson(exchange,
                        "{\"success\":false,\"message\":\"Student name is required.\"}");
                return;
            }

            String id =
                    form.getOrDefault("id", "");

            Student student;

            if (action.equals("edit")) {

                student = students.get(id);

                if (student == null) {
                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Student not found.\"}");
                    return;
                }

            } else {

                id =
                        "STU" + studentCounter++;

                student =
                        new Student(id, name);

                students.put(id, student);
            }

            student.name = name;

            // Make sure every current subject has a mark.
            for (Subject subject : subjects.values()) {

                String key =
                        "mark_" + subject.code;

                String markText =
                        form.get(key);

                if (markText == null ||
                        markText.trim().isEmpty()) {

                    if (action.equals("edit")) {

                        sendJson(exchange,
                                "{\"success\":false,\"message\":\"Please enter marks for every subject.\"}");

                        return;
                    }

                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Please enter marks for every subject.\"}");

                    return;
                }

                int mark;

                try {
                    mark =
                            Integer.parseInt(markText.trim());
                } catch (Exception e) {

                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Marks must be whole numbers.\"}");

                    return;
                }

                // VERY IMPORTANT:
                // Do not allow negative marks.
                if (mark < 0) {

                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Negative marks are not allowed.\"}");

                    return;
                }

                // VERY IMPORTANT:
                // Do not allow marks greater than maximum.
                if (mark > subject.maxMarks) {

                    sendJson(exchange,
                            "{\"success\":false,\"message\":\"Marks for "
                                    + jsonEscape(subject.name)
                                    + " cannot be greater than "
                                    + subject.maxMarks
                                    + ".\"}");

                    return;
                }

                student.marks.put(
                        subject.code,
                        mark
                );
            }

            saveData();

            sendJson(exchange,
                    "{\"success\":true,\"message\":\"Student saved successfully.\"}");

            return;
        }

        if (method.equalsIgnoreCase("DELETE")) {

            Map<String, String> query =
                    parseQuery(exchange.getRequestURI().getRawQuery());

            String id =
                    query.getOrDefault("id", "");

            if (!students.containsKey(id)) {

                sendJson(exchange,
                        "{\"success\":false,\"message\":\"Student not found.\"}");

                return;
            }

            students.remove(id);

            saveData();

            sendJson(exchange,
                    "{\"success\":true,\"message\":\"Student deleted successfully.\"}");

            return;
        }

        sendText(exchange, 405, "Method Not Allowed");
    }

    // =========================
    // DASHBOARD API
    // =========================

    private static void handleDashboard(HttpExchange exchange)
            throws IOException {

        int totalStudents =
                students.size();

        int passed = 0;
        int failed = 0;

        double totalPercentage = 0;
        int completeStudents = 0;

        Student topStudent = null;
        double highestPercentage = -1;

        for (Student student : students.values()) {

            Result result =
                    calculateResult(student);

            if (!result.complete) {
                continue;
            }

            completeStudents++;

            totalPercentage +=
                    result.percentage;

            if (result.passed) {
                passed++;
            } else {
                failed++;
            }

            if (result.percentage >
                    highestPercentage) {

                highestPercentage =
                        result.percentage;

                topStudent =
                        student;
            }
        }

        double classAverage =
                completeStudents == 0
                        ? 0
                        : totalPercentage / completeStudents;

        double passRate =
                completeStudents == 0
                        ? 0
                        : ((double) passed /
                        completeStudents) * 100;

        String topName =
                topStudent == null
                        ? "-"
                        : topStudent.name;

        String json =
                "{"
                        + "\"totalStudents\":" + totalStudents + ","
                        + "\"completeStudents\":" + completeStudents + ","
                        + "\"passed\":" + passed + ","
                        + "\"failed\":" + failed + ","
                        + "\"classAverage\":" + round(classAverage) + ","
                        + "\"passRate\":" + round(passRate) + ","
                        + "\"topStudent\":\"" + jsonEscape(topName) + "\""
                        + "}";

        sendJson(exchange, json);
    }

    // =========================
    // EXPORT CSV
    // =========================

    private static void handleExport(HttpExchange exchange)
            throws IOException {

        if (!exchange.getRequestMethod()
                .equalsIgnoreCase("GET")) {

            sendText(exchange, 405,
                    "Method Not Allowed");

            return;
        }

        StringBuilder csv =
                new StringBuilder();

        csv.append("Student");

        for (Subject subject : subjects.values()) {
            csv.append(",")
                    .append(csvEscape(subject.name));
        }

        csv.append(",Total,Maximum,Percentage,Grade,Status\n");

        for (Student student : students.values()) {

            Result result =
                    calculateResult(student);

            csv.append(csvEscape(student.name));

            for (Subject subject : subjects.values()) {

                Integer mark =
                        student.marks.get(subject.code);

                csv.append(",")
                        .append(mark == null ? "" : mark);
            }

            csv.append(",")
                    .append(result.total)
                    .append(",")
                    .append(result.maxTotal)
                    .append(",")
                    .append(round(result.percentage))
                    .append("%,")
                    .append(csvEscape(result.grade))
                    .append(",")
                    .append(csvEscape(result.status))
                    .append("\n");
        }

        byte[] bytes =
                csv.toString()
                        .getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type",
                        "text/csv; charset=UTF-8");

        exchange.getResponseHeaders()
                .set("Content-Disposition",
                        "attachment; filename=\"student_grades.csv\"");

        exchange.sendResponseHeaders(
                200,
                bytes.length
        );

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(bytes);
        }
    }

    // =========================
    // RESULT CALCULATION
    // =========================

    static class Result {

        int total;
        int maxTotal;
        double percentage;

        int highest = 0;
        int lowest = 0;

        String highestSubject = "";
        String lowestSubject = "";

        String grade = "-";
        String status = "INCOMPLETE";

        boolean passed = false;
        boolean complete = false;
    }

    private static Result calculateResult(
            Student student) {

        Result result =
                new Result();

        if (subjects.isEmpty()) {
            result.status = "NO SUBJECTS";
            return result;
        }

        boolean complete = true;
        boolean allPassed = true;

        int lowestValue =
                Integer.MAX_VALUE;

        int highestValue =
                Integer.MIN_VALUE;

        for (Subject subject : subjects.values()) {

            result.maxTotal +=
                    subject.maxMarks;

            Integer mark =
                    student.marks.get(subject.code);

            if (mark == null) {
                complete = false;
                continue;
            }

            result.total += mark;

            if (mark < PASS_MARK) {
                allPassed = false;
            }

            if (mark > highestValue) {

                highestValue = mark;
                result.highestSubject =
                        subject.name;
            }

            if (mark < lowestValue) {

                lowestValue = mark;
                result.lowestSubject =
                        subject.name;
            }
        }

        result.complete = complete;

        if (!complete) {

            result.status = "INCOMPLETE";
            result.grade = "-";

            if (result.maxTotal > 0) {

                result.percentage =
                        ((double) result.total /
                                result.maxTotal) * 100;
            }

            return result;
        }

        result.highest =
                highestValue;

        result.lowest =
                lowestValue;

        result.percentage =
                result.maxTotal == 0
                        ? 0
                        : ((double) result.total /
                        result.maxTotal) * 100;

        // IMPORTANT:
        // If even ONE subject is below 35,
        // the student fails.
        if (!allPassed) {

            result.grade = "F";
            result.status = "FAIL";
            result.passed = false;

            return result;
        }

        result.passed = true;
        result.status = "PASS";

        double p =
                result.percentage;

        if (p >= 90) {
            result.grade = "A+";
        } else if (p >= 80) {
            result.grade = "A";
        } else if (p >= 70) {
            result.grade = "B";
        } else if (p >= 60) {
            result.grade = "C";
        } else if (p >= 50) {
            result.grade = "D";
        } else {
            result.grade = "F";
        }

        return result;
    }

    // =========================
    // JSON
    // =========================

    private static String studentToJson(
            Student student) {

        Result result =
                calculateResult(student);

        StringBuilder json =
                new StringBuilder();

        json.append("{");

        json.append("\"id\":\"")
                .append(jsonEscape(student.id))
                .append("\",");

        json.append("\"name\":\"")
                .append(jsonEscape(student.name))
                .append("\",");

        json.append("\"total\":")
                .append(result.total)
                .append(",");

        json.append("\"maxTotal\":")
                .append(result.maxTotal)
                .append(",");

        json.append("\"percentage\":")
                .append(round(result.percentage))
                .append(",");

        json.append("\"grade\":\"")
                .append(jsonEscape(result.grade))
                .append("\",");

        json.append("\"status\":\"")
                .append(jsonEscape(result.status))
                .append("\",");

        json.append("\"complete\":")
                .append(result.complete)
                .append(",");

        json.append("\"highest\":")
                .append(result.highest)
                .append(",");

        json.append("\"lowest\":")
                .append(result.lowest)
                .append(",");

        json.append("\"highestSubject\":\"")
                .append(jsonEscape(result.highestSubject))
                .append("\",");

        json.append("\"lowestSubject\":\"")
                .append(jsonEscape(result.lowestSubject))
                .append("\",");

        json.append("\"marks\":{");

        boolean first = true;

        for (Map.Entry<String, Integer> entry :
                student.marks.entrySet()) {

            if (!first) {
                json.append(",");
            }

            json.append("\"")
                    .append(jsonEscape(entry.getKey()))
                    .append("\":")
                    .append(entry.getValue());

            first = false;
        }

        json.append("}");

        json.append("}");

        return json.toString();
    }

    private static String jsonEscape(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String csvEscape(String value) {

        if (value == null) {
            return "";
        }

        return "\"" +
                value.replace("\"", "\"\"") +
                "\"";
    }

    // =========================
    // FORM PARSING
    // =========================

    private static Map<String, String> parseForm(
            HttpExchange exchange)
            throws IOException {

        String body =
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

        return parseQuery(body);
    }

    private static Map<String, String> parseQuery(
            String query) {

        Map<String, String> map =
                new HashMap<>();

        if (query == null ||
                query.isEmpty()) {

            return map;
        }

        String[] pairs =
                query.split("&");

        for (String pair : pairs) {

            String[] parts =
                    pair.split("=", 2);

            String key =
                    urlDecode(parts[0]);

            String value =
                    parts.length > 1
                            ? urlDecode(parts[1])
                            : "";

            map.put(key, value);
        }

        return map;
    }

    private static String urlDecode(
            String value) {

        try {
            return URLDecoder.decode(
                    value,
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            return value;
        }
    }

    // =========================
    // HTTP HELPERS
    // =========================

    private static void sendJson(
            HttpExchange exchange,
            String json)
            throws IOException {

        byte[] bytes =
                json.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set("Content-Type",
                        "application/json; charset=UTF-8");

        exchange.sendResponseHeaders(
                200,
                bytes.length
        );

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(bytes);
        }
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text)
            throws IOException {

        byte[] bytes =
                text.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set("Content-Type",
                        "text/plain; charset=UTF-8");

        exchange.sendResponseHeaders(
                status,
                bytes.length
        );

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(bytes);
        }
    }

    private static void sendHtml(
            HttpExchange exchange,
            String html)
            throws IOException {

        byte[] bytes =
                html.getBytes(
                        StandardCharsets.UTF_8
                );

        exchange.getResponseHeaders()
                .set("Content-Type",
                        "text/html; charset=UTF-8");

        exchange.sendResponseHeaders(
                200,
                bytes.length
        );

        try (OutputStream os =
                     exchange.getResponseBody()) {

            os.write(bytes);
        }
    }

    // =========================
    // PERSISTENCE
    // =========================

    private static void saveData() {

        try (ObjectOutputStream out =
                     new ObjectOutputStream(
                             new FileOutputStream(DATA_FILE)
                     )) {

            DataStore store =
                    new DataStore(
                            subjects,
                            students,
                            subjectCounter,
                            studentCounter
                    );

            out.writeObject(store);

        } catch (Exception e) {

            System.err.println(
                    "Could not save data: "
                            + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadData() {

        Path path =
                Paths.get(DATA_FILE);

        if (!Files.exists(path)) {
            return;
        }

        try (ObjectInputStream in =
                     new ObjectInputStream(
                             new FileInputStream(DATA_FILE)
                     )) {

            DataStore store =
                    (DataStore) in.readObject();

            subjects.clear();
            students.clear();

            subjects.putAll(
                    store.subjects
            );

            students.putAll(
                    store.students
            );

            subjectCounter =
                    store.subjectCounter;

            studentCounter =
                    store.studentCounter;

        } catch (Exception e) {

            System.err.println(
                    "Could not load old data. Starting fresh."
            );
        }
    }

    private static double round(
            double value) {

        return Math.round(
                value * 100.0
        ) / 100.0;
    }

    // ==========================================================
    // FRONTEND
    // ==========================================================

    private static String buildHtml() {

        return """
<!DOCTYPE html>
<html lang="en">

<head>

<meta charset="UTF-8">

<meta name="viewport"
      content="width=device-width, initial-scale=1.0">

<title>EduGrade - Student Grade Management</title>

<style>

* {
    box-sizing: border-box;
}

body {
    margin: 0;
    font-family: Inter, Arial, sans-serif;
    background: #f4f7fb;
    color: #12233f;
}

button,
input {
    font-family: inherit;
}

.app {
    display: flex;
    min-height: 100vh;
}

/* SIDEBAR */

.sidebar {
    width: 248px;
    background: #0d1b33;
    color: white;
    padding: 26px 16px;
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
}

.logo {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 23px;
    font-weight: 800;
    margin-bottom: 46px;
    padding-left: 16px;
}

.logo-icon {
    width: 43px;
    height: 43px;
    background: #19bba6;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 25px;
    font-weight: bold;
}

.menu-title {
    font-size: 12px;
    color: #8294b2;
    padding-left: 12px;
    margin-bottom: 12px;
    text-transform: uppercase;
    letter-spacing: .5px;
}

.menu button {
    width: 100%;
    background: transparent;
    border: none;
    color: #dce5f4;
    padding: 15px 16px;
    text-align: left;
    border-radius: 10px;
    margin-bottom: 6px;
    cursor: pointer;
    font-size: 15px;
}

.menu button:hover,
.menu button.active {
    background: #203a61;
    color: white;
}

.menu-icon {
    width: 24px;
    display: inline-block;
}

/* MAIN */

.main {
    margin-left: 248px;
    padding: 36px;
    width: calc(100% - 248px);
}

.topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 30px;
}

.eyebrow {
    color: #7890b1;
    font-size: 14px;
    margin-bottom: 8px;
}

h1 {
    margin: 0;
    font-size: 32px;
    letter-spacing: .2px;
}

.top-actions {
    display: flex;
    gap: 10px;
}

.btn {
    border: none;
    border-radius: 10px;
    padding: 13px 18px;
    cursor: pointer;
    font-weight: 700;
    font-size: 14px;
}

.btn-primary {
    background: #18b9a5;
    color: white;
}

.btn-primary:hover {
    background: #119e8c;
}

.btn-light {
    background: white;
    color: #19304f;
    border: 1px solid #dbe3ee;
}

.btn-danger {
    background: #ffe8ed;
    color: #e53955;
}

.btn-warning {
    background: #fff2d9;
    color: #ad7600;
}

/* PAGES */

.page {
    display: none;
}

.page.active {
    display: block;
}

/* CARDS */

.cards {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 17px;
    margin-bottom: 20px;
}

.card {
    background: white;
    border: 1px solid #e2e9f2;
    border-radius: 15px;
    padding: 23px;
    box-shadow: 0 5px 18px rgba(20, 44, 75, .04);
}

.card-label {
    color: #7185a3;
    font-size: 13px;
    margin-bottom: 10px;
}

.card-value {
    font-size: 29px;
    font-weight: 800;
}

/* DASHBOARD GRID */

.dashboard-grid {
    display: grid;
    grid-template-columns: 2fr 1fr;
    gap: 20px;
}

.panel {
    background: white;
    border: 1px solid #e2e9f2;
    border-radius: 15px;
    padding: 21px;
    box-shadow: 0 5px 18px rgba(20, 44, 75, .04);
}

.panel-title {
    font-size: 18px;
    font-weight: 800;
    margin-bottom: 20px;
}

.panel-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

/* TABLE */

.table-wrap {
    overflow-x: auto;
}

table {
    width: 100%;
    border-collapse: collapse;
    min-width: 750px;
}

th {
    background: #f7f9fc;
    color: #7185a3;
    font-size: 12px;
    text-transform: uppercase;
    padding: 14px;
    text-align: left;
}

td {
    padding: 15px 14px;
    border-bottom: 1px solid #edf1f6;
    font-size: 14px;
}

.student-name {
    font-weight: 700;
}

.badge {
    display: inline-block;
    padding: 7px 12px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 800;
}

.pass {
    background: #dcf7ef;
    color: #0b9b82;
}

.fail {
    background: #ffe1e6;
    color: #e83d58;
}

.incomplete {
    background: #fff0d4;
    color: #aa7300;
}

/* DONUT */

.chart-card {
    min-height: 330px;
}

.donut {
    width: 205px;
    height: 205px;
    border-radius: 50%;
    margin: 20px auto;
    display: flex;
    align-items: center;
    justify-content: center;
    background: conic-gradient(
        #18b9a5 0deg 360deg,
        #f04d61 360deg
    );
}

.donut-inner {
    width: 135px;
    height: 135px;
    border-radius: 50%;
    background: white;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-direction: column;
}

.donut-number {
    font-size: 28px;
    font-weight: 800;
}

.legend {
    display: flex;
    justify-content: center;
    gap: 25px;
    font-size: 13px;
}

.dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    display: inline-block;
    margin-right: 6px;
}

.dot-pass {
    background: #18b9a5;
}

.dot-fail {
    background: #f04d61;
}

/* SUBJECT PERFORMANCE */

.subject-performance {
    margin-top: 20px;
}

.performance-row {
    margin-bottom: 18px;
}

.performance-header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 7px;
    font-size: 13px;
}

.progress {
    height: 8px;
    border-radius: 20px;
    background: #e9eef5;
    overflow: hidden;
}

.progress-bar {
    height: 100%;
    background: #18b9a5;
    border-radius: 20px;
}

/* FORMS */

.form-panel {
    max-width: 850px;
}

.form-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 17px;
}

.form-group {
    margin-bottom: 18px;
}

.form-group.full {
    grid-column: 1 / -1;
}

label {
    display: block;
    font-size: 13px;
    color: #667b9c;
    margin-bottom: 7px;
    font-weight: 700;
}

input {
    width: 100%;
    padding: 13px 14px;
    border: 1px solid #d8e1ed;
    border-radius: 9px;
    outline: none;
    font-size: 14px;
}

input:focus {
    border-color: #18b9a5;
    box-shadow: 0 0 0 3px rgba(24,185,165,.1);
}

input.invalid {
    border-color: #e83d58;
    background: #fff7f8;
}

.marks-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 15px;
    margin-top: 12px;
}

.subject-mark {
    background: #f8fafc;
    padding: 15px;
    border-radius: 10px;
    border: 1px solid #e4eaf2;
}

.subject-mark-title {
    font-weight: 800;
    margin-bottom: 5px;
}

.subject-max {
    color: #7b8da9;
    font-size: 12px;
    margin-bottom: 9px;
}

/* LIVE RESULT */

.live-result {
    display: grid;
    grid-template-columns: repeat(6, 1fr);
    gap: 10px;
    margin-top: 20px;
}

.result-box {
    background: #f6f9fc;
    border: 1px solid #e3eaf2;
    padding: 13px;
    border-radius: 9px;
}

.result-box small {
    color: #7c8da6;
    display: block;
    margin-bottom: 5px;
}

.result-box strong {
    font-size: 17px;
}

/* SUBJECT CARDS */

.subject-list {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 17px;
    margin-top: 20px;
}

.subject-card {
    background: white;
    border: 1px solid #e1e8f1;
    border-radius: 13px;
    padding: 19px;
}

.subject-card h3 {
    margin: 0 0 8px;
}

.subject-card .max {
    color: #7286a4;
    font-size: 13px;
}

.subject-actions {
    display: flex;
    gap: 8px;
    margin-top: 17px;
}

/* SEARCH */

.search {
    width: 250px;
}

/* EMPTY */

.empty {
    text-align: center;
    color: #8292aa;
    padding: 45px 20px;
}

/* TOAST */

.toast {
    position: fixed;
    right: 25px;
    bottom: 25px;
    background: #10223e;
    color: white;
    padding: 15px 20px;
    border-radius: 10px;
    display: none;
    z-index: 100;
    box-shadow: 0 10px 30px rgba(0,0,0,.2);
}

.toast.show {
    display: block;
}

.toast.error {
    background: #df3d55;
}

.toast.success {
    background: #109c87;
}

/* RESPONSIVE */

@media(max-width: 1100px) {

    .cards {
        grid-template-columns: repeat(2, 1fr);
    }

    .dashboard-grid {
        grid-template-columns: 1fr;
    }

    .subject-list {
        grid-template-columns: repeat(2, 1fr);
    }

    .live-result {
        grid-template-columns: repeat(3, 1fr);
    }
}

@media(max-width: 800px) {

    .sidebar {
        width: 75px;
    }

    .logo span,
    .menu-title,
    .menu button span {
        display: none;
    }

    .logo {
        padding-left: 0;
        justify-content: center;
    }

    .menu button {
        text-align: center;
    }

    .main {
        margin-left: 75px;
        width: calc(100% - 75px);
        padding: 20px;
    }

    .form-grid {
        grid-template-columns: 1fr;
    }

    .marks-grid,
    .subject-list {
        grid-template-columns: 1fr;
    }
}

</style>

</head>

<body>

<div class="app">

    <!-- SIDEBAR -->

    <aside class="sidebar">

        <div class="logo">
            <div class="logo-icon">✓</div>
            <span>EduGrade</span>
        </div>

        <div class="menu-title">
            Main Menu
        </div>

        <div class="menu">

            <button class="active"
                    onclick="showPage('dashboard', this)">
                <span class="menu-icon">▦</span>
                <span>Dashboard</span>
            </button>

            <button onclick="showPage('students', this)">
                <span class="menu-icon">♟</span>
                <span>Students</span>
            </button>

            <button onclick="showPage('subjects', this)">
                <span class="menu-icon">▣</span>
                <span>Subjects</span>
            </button>

            <button onclick="showPage('reports', this)">
                <span class="menu-icon">▤</span>
                <span>Reports</span>
            </button>

        </div>

    </aside>


    <!-- MAIN -->

    <main class="main">

        <!-- ================= DASHBOARD ================= -->

        <section id="dashboard"
                 class="page active">

            <div class="topbar">

                <div>
                    <div class="eyebrow">
                        Academic Overview
                    </div>

                    <h1>
                        Student Grade Management
                    </h1>
                </div>

                <div class="top-actions">

                    <button class="btn btn-light"
                            onclick="exportCSV()">
                        ⇩ Export CSV
                    </button>

                    <button class="btn btn-primary"
                            onclick="openStudentPage()">
                        + Add Student
                    </button>

                </div>

            </div>


            <div class="cards">

                <div class="card">

                    <div class="card-label">
                        Total Students
                    </div>

                    <div class="card-value"
                         id="totalStudents">
                        0
                    </div>

                </div>

                <div class="card">

                    <div class="card-label">
                        Class Average
                    </div>

                    <div class="card-value"
                         id="classAverage">
                        0%
                    </div>

                </div>

                <div class="card">

                    <div class="card-label">
                        Top Performer
                    </div>

                    <div class="card-value"
                         id="topStudent">
                        -
                    </div>

                </div>

                <div class="card">

                    <div class="card-label">
                        Pass Rate
                    </div>

                    <div class="card-value"
                         id="passRate">
                        0%
                    </div>

                </div>

            </div>


            <div class="dashboard-grid">

                <div class="panel">

                    <div class="panel-header">

                        <div class="panel-title">
                            Student Grades
                        </div>

                        <input class="search"
                               id="studentSearch"
                               placeholder="Search students..."
                               oninput="renderStudentsTable()">

                    </div>

                    <div class="table-wrap">

                        <table>

                            <thead>

                            <tr>
                                <th>Student</th>
                                <th>Total</th>
                                <th>Max</th>
                                <th>Percentage</th>
                                <th>Grade</th>
                                <th>Status</th>
                                <th>Action</th>
                            </tr>

                            </thead>

                            <tbody id="studentTableBody">
                            </tbody>

                        </table>

                    </div>

                </div>


                <div>

                    <div class="panel chart-card">

                        <div class="panel-title">
                            Pass / Fail
                        </div>

                        <div class="donut"
                             id="passFailDonut">

                            <div class="donut-inner">

                                <div class="donut-number"
                                     id="passFailNumber">
                                    0%
                                </div>

                                <small>
                                    Pass Rate
                                </small>

                            </div>

                        </div>

                        <div class="legend">

                            <div>
                                <span class="dot dot-pass"></span>
                                Passed <b id="passedCount">0</b>
                            </div>

                            <div>
                                <span class="dot dot-fail"></span>
                                Failed <b id="failedCount">0</b>
                            </div>

                        </div>

                    </div>


                    <div class="panel subject-performance">

                        <div class="panel-title">
                            Subject Performance
                        </div>

                        <div id="performanceList">
                        </div>

                    </div>

                </div>

            </div>

        </section>


        <!-- ================= STUDENTS ================= -->

        <section id="students"
                 class="page">

            <div class="topbar">

                <div>

                    <div class="eyebrow">
                        Student Management
                    </div>

                    <h1>
                        Students
                    </h1>

                </div>

                <button class="btn btn-light"
                        onclick="showPageById('dashboard')">
                    ← Dashboard
                </button>

            </div>


            <div class="panel form-panel">

                <div class="panel-title">
                    <span id="studentFormTitle">
                        Add Student
                    </span>
                </div>

                <input type="hidden"
                       id="studentId">

                <div class="form-group">

                    <label>
                        Student Name
                    </label>

                    <input id="studentName"
                           placeholder="Enter student name"
                           autocomplete="off">

                </div>


                <div class="panel-title"
                     style="margin-top:25px;">
                    Subject Marks
                </div>

                <div id="marksGrid"
                     class="marks-grid">
                </div>


                <div id="noSubjectsMessage"
                     class="empty"
                     style="display:none;">

                    <h3>
                        No subjects available
                    </h3>

                    <p>
                        Add subjects first from the Subjects page.
                    </p>

                    <button class="btn btn-primary"
                            onclick="showPageById('subjects')">
                        + Add Subject
                    </button>

                </div>


                <!-- LIVE RESULT -->

                <div id="liveResult"
                     style="display:none;">

                    <div class="panel-title"
                         style="margin-top:25px;">
                        Live Result
                    </div>

                    <div class="live-result">

                        <div class="result-box">
                            <small>Total</small>
                            <strong id="liveTotal">0</strong>
                        </div>

                        <div class="result-box">
                            <small>Percentage</small>
                            <strong id="livePercentage">0%</strong>
                        </div>

                        <div class="result-box">
                            <small>Highest</small>
                            <strong id="liveHighest">-</strong>
                        </div>

                        <div class="result-box">
                            <small>Lowest</small>
                            <strong id="liveLowest">-</strong>
                        </div>

                        <div class="result-box">
                            <small>Grade</small>
                            <strong id="liveGrade">-</strong>
                        </div>

                        <div class="result-box">
                            <small>Result</small>
                            <strong id="liveStatus">-</strong>
                        </div>

                    </div>

                </div>


                <div style="margin-top:25px;display:flex;gap:10px;">

                    <button class="btn btn-primary"
                            onclick="saveStudent()">
                        Save Student
                    </button>

                    <button class="btn btn-light"
                            onclick="clearStudentForm()">
                        Clear
                    </button>

                </div>

            </div>

        </section>


        <!-- ================= SUBJECTS ================= -->

        <section id="subjects"
                 class="page">

            <div class="topbar">

                <div>

                    <div class="eyebrow">
                        Academic Setup
                    </div>

                    <h1>
                        Subjects
                    </h1>

                </div>

            </div>


            <div class="panel form-panel">

                <div class="panel-title">
                    Add Subject
                </div>

                <div class="form-grid">

                    <div class="form-group">

                        <label>
                            Subject Name
                        </label>

                        <input id="subjectName"
                               placeholder="Example: Java Programming">

                    </div>

                    <div class="form-group">

                        <label>
                            Maximum Marks
                        </label>

                        <input id="subjectMax"
                               type="number"
                               min="35"
                               step="1"
                               placeholder="Example: 100">

                    </div>

                </div>

                <button class="btn btn-primary"
                        onclick="addSubject()">
                    + Add Subject
                </button>

            </div>


            <div class="subject-list"
                 id="subjectList">
            </div>

        </section>


        <!-- ================= REPORTS ================= -->

        <section id="reports"
                 class="page">

            <div class="topbar">

                <div>

                    <div class="eyebrow">
                        Academic Reports
                    </div>

                    <h1>
                        Reports
                    </h1>

                </div>

                <button class="btn btn-primary"
                        onclick="exportCSV()">
                    ⇩ Export CSV
                </button>

            </div>


            <div class="panel">

                <div class="panel-title">
                    Complete Student Report
                </div>

                <div class="table-wrap">

                    <table>

                        <thead>

                        <tr>
                            <th>Student</th>
                            <th>Total</th>
                            <th>Maximum</th>
                            <th>Percentage</th>
                            <th>Highest</th>
                            <th>Lowest</th>
                            <th>Grade</th>
                            <th>Status</th>
                        </tr>

                        </thead>

                        <tbody id="reportsBody">
                        </tbody>

                    </table>

                </div>

            </div>

        </section>

    </main>

</div>


<div id="toast"
     class="toast">
</div>


<script>

let subjects = [];
let students = [];


// =====================================================
// PAGE NAVIGATION
// =====================================================

function showPage(page, button) {

    document.querySelectorAll(".page")
        .forEach(p => p.classList.remove("active"));

    const target =
        document.getElementById(page);

    if (target) {
        target.classList.add("active");
    }

    document.querySelectorAll(".menu button")
        .forEach(b => b.classList.remove("active"));

    if (button) {
        button.classList.add("active");
    }

    if (page === "dashboard") {
        loadDashboard();
    }

    if (page === "students") {
        buildMarkInputs();
    }

    if (page === "subjects") {
        renderSubjects();
    }

    if (page === "reports") {
        renderReports();
    }
}


function showPageById(page) {

    const buttons =
        document.querySelectorAll(".menu button");

    let index = 0;

    if (page === "students") index = 1;
    if (page === "subjects") index = 2;
    if (page === "reports") index = 3;

    showPage(
        page,
        buttons[index]
    );
}


function openStudentPage() {

    showPageById("students");

    clearStudentForm();
}


// =====================================================
// LOAD DATA
// =====================================================

async function loadAll() {

    try {

        const subjectResponse =
            await fetch("/api/subjects");

        subjects =
            await subjectResponse.json();

        const studentResponse =
            await fetch("/api/students");

        students =
            await studentResponse.json();

        renderSubjects();

        buildMarkInputs();

        renderStudentsTable();

        renderReports();

        loadDashboard();

    } catch (error) {

        showToast(
            "Could not load data.",
            true
        );
    }
}


// =====================================================
// SUBJECTS
// =====================================================

async function addSubject() {

    const name =
        document.getElementById(
            "subjectName"
        ).value.trim();

    const maxText =
        document.getElementById(
            "subjectMax"
        ).value.trim();

    if (!name) {

        showToast(
            "Please enter subject name.",
            true
        );

        return;
    }

    if (!maxText) {

        showToast(
            "Please enter maximum marks.",
            true
        );

        return;
    }

    const max =
        Number(maxText);

    if (!Number.isInteger(max)) {

        showToast(
            "Maximum marks must be a whole number.",
            true
        );

        return;
    }

    if (max < 35) {

        showToast(
            "Maximum marks must be at least 35.",
            true
        );

        return;
    }

    const params =
        new URLSearchParams();

    params.append("action", "add");
    params.append("name", name);
    params.append("maxMarks", max);


    const response =
        await fetch(
            "/api/subjects",
            {
                method: "POST",
                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body: params.toString()
            }
        );

    const result =
        await response.json();

    if (!result.success) {

        showToast(
            result.message,
            true
        );

        return;
    }

    document.getElementById(
        "subjectName"
    ).value = "";

    document.getElementById(
        "subjectMax"
    ).value = "";

    showToast(
        "Subject added successfully."
    );

    await loadAll();
}


function renderSubjects() {

    const container =
        document.getElementById(
            "subjectList"
        );

    if (!container) {
        return;
    }

    if (subjects.length === 0) {

        container.innerHTML = `
            <div class="panel empty"
                 style="grid-column:1/-1;">
                <h3>No subjects yet</h3>
                <p>
                    Add your first subject above.
                </p>
            </div>
        `;

        return;
    }

    container.innerHTML =
        subjects.map(subject => `

            <div class="subject-card">

                <h3>
                    ${escapeHtml(subject.name)}
                </h3>

                <div class="max">
                    Maximum Marks:
                    <strong>
                        ${subject.maxMarks}
                    </strong>
                </div>

                <div class="max"
                     style="margin-top:6px;">
                    Minimum Pass:
                    <strong>35</strong>
                </div>

                <div class="subject-actions">

                    <button class="btn btn-warning"
                            onclick="editSubject(
                                '${subject.code}'
                            )">
                        Edit
                    </button>

                    <button class="btn btn-danger"
                            onclick="deleteSubject(
                                '${subject.code}'
                            )">
                        Delete
                    </button>

                </div>

            </div>

        `).join("");
}


async function editSubject(code) {

    const subject =
        subjects.find(
            s => s.code === code
        );

    if (!subject) {
        return;
    }

    const newName =
        prompt(
            "Enter subject name:",
            subject.name
        );

    if (newName === null) {
        return;
    }

    const newMax =
        prompt(
            "Enter maximum marks:",
            subject.maxMarks
        );

    if (newMax === null) {
        return;
    }

    const max =
        Number(newMax);

    if (!Number.isInteger(max) ||
        max < 35) {

        showToast(
            "Maximum marks must be a whole number of at least 35.",
            true
        );

        return;
    }

    const params =
        new URLSearchParams();

    params.append("action", "edit");
    params.append("code", code);
    params.append("name", newName.trim());
    params.append("maxMarks", max);

    const response =
        await fetch(
            "/api/subjects",
            {
                method: "POST",
                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body: params.toString()
            }
        );

    const result =
        await response.json();

    if (!result.success) {

        showToast(
            result.message,
            true
        );

        return;
    }

    showToast(
        "Subject updated successfully."
    );

    await loadAll();
}


async function deleteSubject(code) {

    const subject =
        subjects.find(
            s => s.code === code
        );

    if (!subject) {
        return;
    }

    const confirmed =
        confirm(
            "Delete subject '" +
            subject.name +
            "'?\\n\\n" +
            "This will remove this subject's marks from all students."
        );

    if (!confirmed) {
        return;
    }

    const response =
        await fetch(
            "/api/subjects?code=" +
            encodeURIComponent(code),
            {
                method: "DELETE"
            }
        );

    const result =
        await response.json();

    if (!result.success) {

        showToast(
            result.message,
            true
        );

        return;
    }

    showToast(
        "Subject deleted."
    );

    await loadAll();
}


// =====================================================
// DYNAMIC STUDENT MARK INPUTS
// =====================================================

function buildMarkInputs() {

    const grid =
        document.getElementById(
            "marksGrid"
        );

    const noSubjects =
        document.getElementById(
            "noSubjectsMessage"
        );

    const liveResult =
        document.getElementById(
            "liveResult"
        );

    if (!grid) {
        return;
    }

    if (subjects.length === 0) {

        grid.innerHTML = "";

        noSubjects.style.display =
            "block";

        liveResult.style.display =
            "none";

        return;
    }

    noSubjects.style.display =
        "none";

    liveResult.style.display =
        "block";


    grid.innerHTML =
        subjects.map(subject => `

            <div class="subject-mark">

                <div class="subject-mark-title">
                    ${escapeHtml(subject.name)}
                </div>

                <div class="subject-max">
                    Maximum:
                    ${subject.maxMarks}
                    &nbsp; | &nbsp;
                    Pass:
                    35
                </div>

                <input
                    type="number"
                    class="mark-input"
                    id="mark_${subject.code}"
                    data-code="${subject.code}"
                    min="0"
                    max="${subject.maxMarks}"
                    step="1"
                    placeholder="Enter marks"
                    oninput="validateMarkInput(this); calculateLiveResult()"
                >

                <div class="mark-error"
                     id="error_${subject.code}"
                     style="
                       color:#e83d58;
                       font-size:11px;
                       margin-top:5px;
                       min-height:14px;
                     ">
                </div>

            </div>

        `).join("");
}


// =====================================================
// MARK VALIDATION
// =====================================================

function validateMarkInput(input) {

    const code =
        input.dataset.code;

    const subject =
        subjects.find(
            s => s.code === code
        );

    if (!subject) {
        return;
    }

    const error =
        document.getElementById(
            "error_" + code
        );

    let value =
        input.value;

    // Prevent negative sign.
    if (value.includes("-")) {

        input.value =
            value.replace(/-/g, "");

        value =
            input.value;

        error.textContent =
            "Negative marks are not allowed.";

        input.classList.add("invalid");

        return;
    }

    if (value === "") {

        error.textContent = "";

        input.classList.remove(
            "invalid"
        );

        return;
    }

    let number =
        Number(value);

    // Prevent decimals.
    if (!Number.isInteger(number)) {

        input.value =
            Math.floor(number);

        number =
            Number(input.value);
    }

    // Prevent greater than maximum.
    if (number > subject.maxMarks) {

        input.value =
            subject.maxMarks;

        error.textContent =
            "Maximum allowed: " +
            subject.maxMarks;

        input.classList.add(
            "invalid"
        );

        return;
    }

    if (number < 0) {

        input.value = 0;

        error.textContent =
            "Negative marks are not allowed.";

        input.classList.add(
            "invalid"
        );

        return;
    }

    error.textContent = "";

    input.classList.remove(
        "invalid"
    );
}


// =====================================================
// LIVE CALCULATION
// =====================================================

function calculateLiveResult() {

    if (subjects.length === 0) {
        return;
    }

    let total = 0;
    let maxTotal = 0;

    let highest = -1;
    let lowest = Infinity;

    let highestSubject = "";
    let lowestSubject = "";

    let complete = true;
    let allPassed = true;

    subjects.forEach(subject => {

        maxTotal +=
            subject.maxMarks;

        const input =
            document.getElementById(
                "mark_" + subject.code
            );

        if (!input ||
            input.value === "") {

            complete = false;
            return;
        }

        let mark =
            Number(input.value);

        // Extra protection in frontend.
        if (mark < 0) {
            mark = 0;
        }

        if (mark > subject.maxMarks) {
            mark = subject.maxMarks;
        }

        total += mark;

        if (mark < 35) {
            allPassed = false;
        }

        if (mark > highest) {

            highest = mark;
            highestSubject =
                subject.name;
        }

        if (mark < lowest) {

            lowest = mark;
            lowestSubject =
                subject.name;
        }
    });


    const percentage =
        maxTotal === 0
            ? 0
            : (total / maxTotal) * 100;


    let grade = "-";
    let status = "INCOMPLETE";


    if (complete) {

        if (!allPassed) {

            grade = "F";
            status = "FAIL";

        } else {

            status = "PASS";

            if (percentage >= 90) {
                grade = "A+";
            } else if (percentage >= 80) {
                grade = "A";
            } else if (percentage >= 70) {
                grade = "B";
            } else if (percentage >= 60) {
                grade = "C";
            } else if (percentage >= 50) {
                grade = "D";
            } else {
                grade = "F";
            }
        }
    }


    document.getElementById(
        "liveTotal"
    ).textContent =
        total + " / " + maxTotal;

    document.getElementById(
        "livePercentage"
    ).textContent =
        percentage.toFixed(2) + "%";

    document.getElementById(
        "liveHighest"
    ).textContent =
        complete
            ? highest + " (" + highestSubject + ")"
            : "-";

    document.getElementById(
        "liveLowest"
    ).textContent =
        complete
            ? lowest + " (" + lowestSubject + ")"
            : "-";

    document.getElementById(
        "liveGrade"
    ).textContent =
        grade;

    document.getElementById(
        "liveStatus"
    ).textContent =
        status;
}


// =====================================================
// SAVE STUDENT
// =====================================================

async function saveStudent() {

    const name =
        document.getElementById(
            "studentName"
        ).value.trim();

    if (!name) {

        showToast(
            "Please enter student name.",
            true
        );

        return;
    }

    if (subjects.length === 0) {

        showToast(
            "Please add subjects first.",
            true
        );

        return;
    }


    const params =
        new URLSearchParams();

    const id =
        document.getElementById(
            "studentId"
        ).value;

    params.append(
        "action",
        id ? "edit" : "add"
    );

    if (id) {
        params.append("id", id);
    }

    params.append(
        "name",
        name
    );


    let valid = true;


    subjects.forEach(subject => {

        const input =
            document.getElementById(
                "mark_" + subject.code
            );

        if (!input ||
            input.value === "") {

            valid = false;

            return;
        }

        const mark =
            Number(input.value);

        if (!Number.isInteger(mark) ||
            mark < 0 ||
            mark > subject.maxMarks) {

            valid = false;

            return;
        }

        params.append(
            "mark_" + subject.code,
            mark
        );
    });


    if (!valid) {

        showToast(
            "Please enter valid marks for every subject. Marks cannot be negative or greater than maximum marks.",
            true
        );

        return;
    }


    const response =
        await fetch(
            "/api/students",
            {
                method: "POST",
                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body: params.toString()
            }
        );

    const result =
        await response.json();


    if (!result.success) {

        showToast(
            result.message,
            true
        );

        return;
    }


    showToast(
        id
            ? "Student updated successfully."
            : "Student added successfully."
    );

    clearStudentForm();

    await loadAll();

    showPageById("dashboard");
}


// =====================================================
// EDIT STUDENT
// =====================================================

function editStudent(id) {

    const student =
        students.find(
            s => s.id === id
        );

    if (!student) {
        return;
    }

    showPageById("students");

    document.getElementById(
        "studentFormTitle"
    ).textContent =
        "Edit Student";

    document.getElementById(
        "studentId"
    ).value =
        student.id;

    document.getElementById(
        "studentName"
    ).value =
        student.name;

    buildMarkInputs();

    subjects.forEach(subject => {

        const input =
            document.getElementById(
                "mark_" + subject.code
            );

        if (input &&
            student.marks[subject.code] !== undefined) {

            input.value =
                student.marks[subject.code];
        }

    });

    calculateLiveResult();
}


// =====================================================
// CLEAR STUDENT
// =====================================================

function clearStudentForm() {

    const id =
        document.getElementById(
            "studentId"
        );

    const name =
        document.getElementById(
            "studentName"
        );

    if (id) {
        id.value = "";
    }

    if (name) {
        name.value = "";
    }

    document.getElementById(
        "studentFormTitle"
    ).textContent =
        "Add Student";

    buildMarkInputs();

    subjects.forEach(subject => {

        const input =
            document.getElementById(
                "mark_" + subject.code
            );

        if (input) {
            input.value = "";
        }

    });

    calculateLiveResult();
}


// =====================================================
// DELETE STUDENT
// =====================================================

async function deleteStudent(id) {

    const student =
        students.find(
            s => s.id === id
        );

    if (!student) {
        return;
    }

    if (!confirm(
        "Delete student '" +
        student.name +
        "'?"
    )) {
        return;
    }


    const response =
        await fetch(
            "/api/students?id=" +
            encodeURIComponent(id),
            {
                method: "DELETE"
            }
        );

    const result =
        await response.json();


    if (!result.success) {

        showToast(
            result.message,
            true
        );

        return;
    }

    showToast(
        "Student deleted."
    );

    await loadAll();
}


// =====================================================
// STUDENT TABLE
// =====================================================

function renderStudentsTable() {

    const body =
        document.getElementById(
            "studentTableBody"
        );

    if (!body) {
        return;
    }

    const search =
        (
            document.getElementById(
                "studentSearch"
            )?.value || ""
        ).toLowerCase();


    const filtered =
        students.filter(
            student =>
                student.name
                    .toLowerCase()
                    .includes(search)
        );


    if (filtered.length === 0) {

        body.innerHTML = `
            <tr>
                <td colspan="7"
                    class="empty">
                    No students found.
                </td>
            </tr>
        `;

        return;
    }


    body.innerHTML =
        filtered.map(student => {

            let badgeClass =
                "incomplete";

            if (student.status === "PASS") {
                badgeClass = "pass";
            }

            if (student.status === "FAIL") {
                badgeClass = "fail";
            }


            return `

                <tr>

                    <td class="student-name">
                        ${escapeHtml(student.name)}
                    </td>

                    <td>
                        ${student.total}
                    </td>

                    <td>
                        ${student.maxTotal}
                    </td>

                    <td>
                        <strong>
                            ${student.percentage.toFixed(2)}%
                        </strong>
                    </td>

                    <td>
                        ${escapeHtml(student.grade)}
                    </td>

                    <td>
                        <span class="badge ${badgeClass}">
                            ${escapeHtml(student.status)}
                        </span>
                    </td>

                    <td>

                        <button class="btn btn-warning"
                                onclick="editStudent(
                                    '${student.id}'
                                )">
                            Edit
                        </button>

                        <button class="btn btn-danger"
                                onclick="deleteStudent(
                                    '${student.id}'
                                )">
                            Delete
                        </button>

                    </td>

                </tr>

            `;

        }).join("");
}


// =====================================================
// DASHBOARD
// =====================================================

async function loadDashboard() {

    const response =
        await fetch(
            "/api/dashboard"
        );

    const data =
        await response.json();


    document.getElementById(
        "totalStudents"
    ).textContent =
        data.totalStudents;


    document.getElementById(
        "classAverage"
    ).textContent =
        data.classAverage.toFixed(2) + "%";


    document.getElementById(
        "topStudent"
    ).textContent =
        data.topStudent;


    document.getElementById(
        "passRate"
    ).textContent =
        data.passRate.toFixed(0) + "%";


    document.getElementById(
        "passFailNumber"
    ).textContent =
        data.passRate.toFixed(0) + "%";


    document.getElementById(
        "passedCount"
    ).textContent =
        data.passed;


    document.getElementById(
        "failedCount"
    ).textContent =
        data.failed;


    updateDonut(
        data.passed,
        data.failed
    );


    renderSubjectPerformance();
}


// =====================================================
// DONUT CHART
// =====================================================

function updateDonut(
    passed,
    failed
) {

    const total =
        passed + failed;

    const donut =
        document.getElementById(
            "passFailDonut"
        );

    if (total === 0) {

        donut.style.background =
            "#e8edf4";

        return;
    }

    const passedDegrees =
        (passed / total) * 360;

    donut.style.background =
        "conic-gradient(" +
        "#18b9a5 0deg " +
        passedDegrees + "deg, " +
        "#f04d61 " +
        passedDegrees + "deg 360deg)";
}


// =====================================================
// SUBJECT PERFORMANCE
// =====================================================

function renderSubjectPerformance() {

    const container =
        document.getElementById(
            "performanceList"
        );

    if (!container) {
        return;
    }


    if (subjects.length === 0) {

        container.innerHTML =
            `<div class="empty">
                Add subjects to see performance.
             </div>`;

        return;
    }


    container.innerHTML =
        subjects.map(subject => {

            let total = 0;
            let count = 0;

            students.forEach(student => {

                const mark =
                    student.marks[subject.code];

                if (mark !== undefined) {

                    total += Number(mark);
                    count++;
                }

            });


            const average =
                count === 0
                    ? 0
                    : total /
                    (count * subject.maxMarks) *
                    100;


            return `

                <div class="performance-row">

                    <div class="performance-header">

                        <span>
                            ${escapeHtml(subject.name)}
                        </span>

                        <strong>
                            ${average.toFixed(0)}%
                        </strong>

                    </div>

                    <div class="progress">

                        <div class="progress-bar"
                             style="width:${Math.min(
                                 100,
                                 Math.max(
                                     0,
                                     average
                                 )
                             )}%">
                        </div>

                    </div>

                </div>

            `;

        }).join("");
}


// =====================================================
// REPORTS
// =====================================================

function renderReports() {

    const body =
        document.getElementById(
            "reportsBody"
        );

    if (!body) {
        return;
    }


    if (students.length === 0) {

        body.innerHTML = `
            <tr>
                <td colspan="8"
                    class="empty">
                    No student records yet.
                </td>
            </tr>
        `;

        return;
    }


    body.innerHTML =
        students.map(student => {

            let badgeClass =
                "incomplete";

            if (student.status === "PASS") {
                badgeClass = "pass";
            }

            if (student.status === "FAIL") {
                badgeClass = "fail";
            }


            const highest =
                student.highest > 0
                    ? student.highest +
                      " (" +
                      escapeHtml(
                          student.highestSubject
                      ) +
                      ")"
                    : "-";


            const lowest =
                student.complete
                    ? student.lowest +
                      " (" +
                      escapeHtml(
                          student.lowestSubject
                      ) +
                      ")"
                    : "-";


            return `

                <tr>

                    <td class="student-name">
                        ${escapeHtml(student.name)}
                    </td>

                    <td>
                        ${student.total}
                    </td>

                    <td>
                        ${student.maxTotal}
                    </td>

                    <td>
                        ${student.percentage.toFixed(2)}%
                    </td>

                    <td>
                        ${highest}
                    </td>

                    <td>
                        ${lowest}
                    </td>

                    <td>
                        ${escapeHtml(student.grade)}
                    </td>

                    <td>
                        <span class="badge ${badgeClass}">
                            ${escapeHtml(student.status)}
                        </span>
                    </td>

                </tr>

            `;

        }).join("");
}


// =====================================================
// EXPORT
// =====================================================

function exportCSV() {

    window.location.href =
        "/api/export";
}


// =====================================================
// TOAST
// =====================================================

function showToast(
    message,
    error = false
) {

    const toast =
        document.getElementById(
            "toast"
        );

    toast.textContent =
        message;

    toast.className =
        "toast show " +
        (error ? "error" : "success");


    setTimeout(() => {

        toast.className =
            "toast";

    }, 3000);
}


// =====================================================
// ESCAPE HTML
// =====================================================

function escapeHtml(value) {

    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}


// =====================================================
// START
// =====================================================

loadAll();

</script>

</body>
</html>
""";
    }
}