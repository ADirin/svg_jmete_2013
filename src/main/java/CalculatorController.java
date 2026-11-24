import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.LocalizationService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST API used by the browser page (static/index.html) and by JMeter.
 *
 *   GET      /api/health
 *   GET      /api/languages
 *   GET      /api/i18n?lang=fi
 *   GET|POST /api/calculate?distance=120&speed=60&lang=fa&save=true
 *   GET      /api/history?lang=sv&limit=5
 *
 * Parameters work both as query string and as form fields (application/x-www-form-urlencoded).
 */
@RestController // methods return JSON,
@CrossOrigin // allow browser page to call the API even though it is served from a different port (8083 vs 8080)
@RequestMapping("/api")
public class CalculatorController {

    /** Same languages as the JavaFX UI. */
    record Lang(String code, String label, String tag, boolean rtl) {
        Locale locale() { return code.equals("en") ? Locale.ROOT : Locale.forLanguageTag(tag); }
        boolean persian() { return code.equals("fa"); }
        boolean localDigits() { return code.equals("fa") || code.equals("ar"); }
    }

    private static final Map<String, Lang> LANGS = new LinkedHashMap<>();
    static {
        for (Lang l : List.of(
                new Lang("en", "English", "en", false),
                new Lang("fi", "Suomi", "fi-FI", false),
                new Lang("sv", "Svenska", "sv-SE", false),
                new Lang("ja", "日本語", "ja-JP", false),
                new Lang("ar", "العربية", "ar", true),
                new Lang("fa", "فارسی", "fa-IR", true))) {
            LANGS.put(l.code(), l);
        }
    }

    private static final int DEFAULT_HISTORY = 5;

    private final UnitDao dao;

    public CalculatorController(UnitDao dao) {
        this.dao = dao;
    }

    // ------------------------------------------------------------------ endpoints

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/languages")
    public List<Map<String, Object>> languages() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Lang l : LANGS.values()) {
            out.add(Map.of("code", l.code(), "label", l.label(), "rtl", l.rtl()));
        }
        return out;
    }

    /** Localized UI labels + unit names for the selected language. */
    @GetMapping("/i18n")
    public Map<String, Object> i18n(@RequestParam(name = "lang", defaultValue = "en") String lang) {
        Lang l = resolve(lang);
        LocalizationService loc = localization(l);
        Map<String, UnitDao.Unit> units = loadUnits(l);

        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("language", loc.get("language"));
        labels.put("distance", loc.get("distance") + " (" + unitName(units, "KM") + ")");
        labels.put("speed", loc.get("speed") + " (" + unitName(units, "KMH") + ")");
        labels.put("calculate", loc.get("button.calculate"));
        labels.put("historyTitle", loc.get("history.title"));

        Map<String, Object> unitInfo = new LinkedHashMap<>();
        units.forEach((code, u) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", u.name());
            m.put("description", u.description());
            unitInfo.put(code, m);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lang", l.code());
        body.put("rtl", l.rtl());
        body.put("labels", labels);
        body.put("units", unitInfo);
        if (units.isEmpty()) body.put("dbMessage", loc.get("db.error"));
        return body;
    }

    @RequestMapping(value = "/calculate", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> calculate(
            @RequestParam(name = "distance", required = false) String distance,
            @RequestParam(name = "speed", required = false) String speed,
            @RequestParam(name = "lang", defaultValue = "en") String lang,
            @RequestParam(name = "save", defaultValue = "true") boolean save) {

        Lang l = resolve(lang);
        LocalizationService loc = localization(l);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lang", l.code());

        double d, s;
        try {
            d = parseNumber(distance);
            s = parseNumber(speed);
        } catch (NumberFormatException | NullPointerException e) {
            body.put("error", loc.get("error.invalidInput"));
            return ResponseEntity.badRequest().body(body);
        }
        if (s <= 0 || d < 0) {
            body.put("error", loc.get("error.invalidInput"));
            return ResponseEntity.badRequest().body(body);
        }

        double time = calTime(d, s);
        Map<String, UnitDao.Unit> units = loadUnits(l);
        String result = loc.format("calc.result", d, s, time,
                unitName(units, "KM"), unitName(units, "KMH"), unitName(units, "H"));

        body.put("distance", d);
        body.put("speed", s);
        body.put("time", time);
        body.put("result", localizeDigits(result, l));
        body.put("message", loc.get("message"));

        if (save) {
            try {
                dao.saveCalculation(d, s, time, l.code(), "KM", "KMH");
                body.put("db", "saved");
                body.put("dbMessage", loc.get("db.saved"));
            } catch (SQLException e) {
                e.printStackTrace();
                body.put("db", "error");
                body.put("dbMessage", loc.get("db.error"));
            }
        } else {
            body.put("db", "skipped");
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(name = "lang", defaultValue = "en") String lang,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_HISTORY) int limit) {

        Lang l = resolve(lang);
        LocalizationService loc = localization(l);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", loc.get("history.title"));

        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (UnitDao.HistoryRow r : dao.lastCalculations(l.code(), Math.max(1, Math.min(limit, 100)))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("distance", r.distance());
                row.put("distanceUnit", r.distanceUnit());
                row.put("speed", r.speed());
                row.put("speedUnit", r.speedUnit());
                row.put("timeHours", r.timeHours());
                row.put("text", localizeDigits(loc.format("history.row",
                        r.distance(), r.distanceUnit(), r.speed(), r.speedUnit(), r.timeHours()), l));
                rows.add(row);
            }
            body.put("rows", rows);
            return ResponseEntity.ok(body);
        } catch (SQLException e) {
            e.printStackTrace();
            body.put("error", loc.get("db.error"));
            return ResponseEntity.status(503).body(body);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Lang resolve(String lang) {
        String code = (lang == null || lang.length() < 2) ? "en" : lang.substring(0, 2).toLowerCase(Locale.ROOT);
        return LANGS.getOrDefault(code, LANGS.get("en"));
    }

    /** A new instance per request: LocalizationService holds the current locale, so it is not shared between threads. */
    private static LocalizationService localization(Lang l) {
        LocalizationService loc = new LocalizationService(l.locale());
        loc.loadLocale(l.locale());
        return loc;
    }

    private Map<String, UnitDao.Unit> loadUnits(Lang l) {
        try {
            return dao.findAll(l.code());
        } catch (SQLException e) {
            e.printStackTrace();
            return Map.of();
        }
    }

    private static String unitName(Map<String, UnitDao.Unit> units, String code) {
        UnitDao.Unit u = units.get(code);
        return u != null ? u.name() : code;
    }

    public static double calTime(double distance, double speed) {
        if (distance < 0 || speed <= 0) {
            return 0;
        }
        return distance / speed;
    }

    /** Accepts ASCII, Persian and Arabic-Indic digits. */
    private static double parseNumber(String text) {
        return Double.parseDouble(normalizeDigits(text.trim()));
    }

    /** Converts Persian (۰-۹) and Arabic-Indic (٠-٩) digits and decimal separators to plain ASCII. */
    static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c == '٫' || c == ',' || c == '،') {
                sb.append('.');
            } else if (c == '٬' || Character.isWhitespace(c)) {
                // thousands separator / whitespace: dropped
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Converts ASCII digits in a sentence to Persian / Arabic-Indic digits.
     * Unlike normalizeDigits(), spaces and commas are kept, and '.' becomes '٫'
     * only when it sits between two digits (a decimal point, not a full stop).
     */
    static String localizeDigits(String s, Lang l) {
        if (s == null || !l.localDigits()) return s;
        char zero = l.persian() ? '۰' : '٠';
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append((char) (zero + (c - '0')));
            } else if (c == '.' && i > 0 && i < s.length() - 1
                    && Character.isDigit(s.charAt(i - 1)) && Character.isDigit(s.charAt(i + 1))) {
                sb.append('٫');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
