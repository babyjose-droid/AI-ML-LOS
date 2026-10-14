package com.rhythm.los.collateral;

import com.rhythm.los.application.ApplicationRequest;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import com.rhythm.los.workflow.DemoDataLoader.BytesFile;
import com.rhythm.los.workflow.ProcessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The three Collateral AI cases of the prototype, on synthetic data: a residential LAP that passes, a shop that is
 * referred (open charge, CTS mismatch, 25% valuation gap) and a plot that fails (sold to a third party in 2024,
 * a reused photo, GPS 2.3 km off). Loaded once when demo data is on and there is no collateral yet.
 */
@Component
public class CollateralDemoLoader {
    private static final Logger log = LoggerFactory.getLogger(CollateralDemoLoader.class);
    static final CurrentUser SALES = new CurrentUser("sales1", Role.SALES, "Sameer Kulkarni", 4L);
    static final CurrentUser OPS = new CurrentUser("ops1", Role.OPERATIONS, "Neha Joshi", 4L);
    static final CurrentUser FIELD = new CurrentUser("field1", Role.FIELD_OFFICER, "Ganesh Pawar", 4L);
    static final CurrentUser VALUER = new CurrentUser("valuer1", Role.VALUER, "Er. Prakash Deshmukh", null);

    private final boolean enabled;
    private final CollateralRepository collaterals;
    private final CollateralService service;
    private final ApplicationService apps;
    private final DocumentService docs;
    private final ProcessService process;

    public CollateralDemoLoader(@Value("${rhythm.demo-data}") boolean enabled, CollateralRepository collaterals, CollateralService service,
                                ApplicationService apps, DocumentService docs, ProcessService process) {
        this.enabled = enabled;
        this.collaterals = collaterals;
        this.service = service;
        this.apps = apps;
        this.docs = docs;
        this.process = process;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    public void load() {
        if (!enabled || collaterals.count() > 0) return;
        log.info("Loading Collateral AI demo cases");
        byte[] shared = photo(11);
        LocalDate t = LocalDate.now();
        String ecTo = t.minusDays(10).toString();
        step("COL residential", () -> {
            LoanApplication a = app("Vikas Deshpande", "VIKPD1002V", "9822002001", "422013", "Nashik", "Deshpande Traders", 4_675_000);
            Collateral c = service.declare(a.getId(), declare("RESIDENTIAL", "Row house, Gangapur Road", "Vikas Deshpande", "Plot 14, Gangapur Road, Nashik", "422013",
                    20.0063, 73.7627, "CTS 1234/5", "NMC-44521", 1850.0), SALES);
            doc(c, "SALE_DEED", Map.of("owner", "Vikas Deshpande", "seller", "Anil Kulkarni", "surveyNo", "CTS1234/5", "areaSqft", 1850, "registrationNo", "4521/2016", "registrationDate", "2016-05-12"), null);
            doc(c, "EC", Map.of("periodFrom", "2012-01-01", "periodTo", ecTo, "surveyNo", "CTS1234/5"), List.of(
                    tx("2016-05-12", "SALE", "4521/2016", "Anil Kulkarni", "Vikas Deshpande", null),
                    tx("2016-06-20", "MORTGAGE", "4890/2016", "Vikas Deshpande", "State Bank of India", null),
                    tx("2021-03-15", "RELEASE", "2210/2021", "State Bank of India", "Vikas Deshpande", "4890/2016")));
            doc(c, "KHATA", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC-44521"), null);
            doc(c, "PROPERTY_TAX", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC-44521", "taxPaidUpTo", "2026-27"), null);
            doc(c, "VALUATION_REPORT", Map.of("marketValue", 8_500_000, "valuer", "Er. P Deshmukh"), null);
            service.addValuation(c.getId(), new CollateralService.ValuationRequest("Er. P Deshmukh (empanelled)", t.minusDays(20).toString(), 8_500_000d, 7_650_000d, 6_375_000d, "Sales comparison"), VALUER);
            // above ₹50 lakh the policy asks for a second valuation
            service.addValuation(c.getId(), new CollateralService.ValuationRequest("Er. R Bhide (empanelled)", t.minusDays(15).toString(), 8_900_000d, 8_000_000d, 6_700_000d, "Sales comparison"), VALUER);
            photos(c, List.of("EXTERIOR_FRONT", "EXTERIOR_SIDE", "INTERIOR_HALL", "INTERIOR_KITCHEN", "STREET_VIEW"), 20.0064, 73.7628, shared, 1);
            service.update(c.getId(), declare(null, null, null, null, null, null, null, null, null, null), FIELD);
        });
        step("COL shop", () -> {
            LoanApplication a = app("Farhan Shaikh", "FARPS1002F", "9822002002", "422001", "Nashik", "Shaikh Mobile Gallery", 3_200_000);
            Collateral c = service.declare(a.getId(), declare("SHOP", "Shop 7, ground floor, Main Road", "Farhan Shaikh", "Shop 7, Main Road, Nashik", "422001",
                    19.9975, 73.7898, "CTS 778/2", "NMC-90211", 520.0), SALES);
            doc(c, "SALE_DEED", Map.of("owner", "Farhan Shaikh", "seller", "Kishor Jain", "surveyNo", "CTS778/2", "areaSqft", 520, "registrationNo", "3301/2014", "registrationDate", "2014-08-02"), null);
            doc(c, "EC", Map.of("periodFrom", "2012-04-01", "periodTo", ecTo, "surveyNo", "CTS778/3"), List.of(
                    tx("2014-08-02", "SALE", "3301/2014", "Kishor Jain", "Farhan Shaikh", null),
                    tx("2019-02-11", "MORTGAGE", "1187/2019", "Farhan Shaikh", "Janata Sahakari Bank", null)));
            doc(c, "KHATA", Map.of("owner", "Farhan Shaikh", "municipalId", "NMC-90211"), null);
            doc(c, "PROPERTY_TAX", Map.of("owner", "Farhan Shaikh", "municipalId", "NMC-90211", "taxPaidUpTo", "2026-27"), null);
            doc(c, "VALUATION_REPORT", Map.of("marketValue", 4_950_000, "valuer", "Er. S Kale"), null);
            service.addValuation(c.getId(), new CollateralService.ValuationRequest("Er. S Kale (empanelled)", t.minusDays(30).toString(), 4_950_000d, 4_450_000d, 3_700_000d, "Sales comparison"), VALUER);
            service.addValuation(c.getId(), new CollateralService.ValuationRequest("Er. M Patwardhan (empanelled)", t.minusDays(12).toString(), 6_187_500d, 5_500_000d, 4_600_000d, "Income capitalisation"), VALUER);
            photos(c, List.of("EXTERIOR_FRONT", "SIGNAGE", "INTERIOR_HALL", "STREET_VIEW", "NEIGHBOURHOOD"), 19.9976, 73.7899, null, 20);
        });
        step("COL plot", () -> {
            LoanApplication a = app("Rajesh Gupta", "RAJPG1004G", "9822002003", "208023", "Kanpur", "Gupta Steel Traders", 2_500_000);
            Collateral c = service.declare(a.getId(), declare("PLOT", "Commercial plot, Panki industrial area", "Rajesh Gupta", "Plot C-41, Panki, Kanpur", "208023",
                    26.4710, 80.2410, "GATA 512", "KNN-7781", 4000.0), SALES);
            doc(c, "SALE_DEED", Map.of("owner", "Rajesh Gupta", "seller", "Om Prakash Singh", "surveyNo", "GATA512", "areaSqft", 4000, "registrationNo", "6612/2018", "registrationDate", "2018-11-20"), null);
            doc(c, "EC", Map.of("periodFrom", "1995-01-01", "periodTo", ecTo, "surveyNo", "GATA512"), List.of(
                    tx("2018-11-20", "SALE", "6612/2018", "Om Prakash Singh", "Rajesh Gupta", null),
                    tx("2024-07-09", "SALE", "5120/2024", "Rajesh Gupta", "Manoj Verma", null)));
            doc(c, "LAND_RECORD", Map.of("owner", "Rajesh Gupta", "surveyNo", "GATA512", "permittedUse", "Commercial"), null);
            doc(c, "CONVERSION_ORDER", Map.of("surveyNo", "GATA512", "permittedUse", "Commercial (non-agricultural)"), null);
            doc(c, "PROPERTY_TAX", Map.of("owner", "Rajesh Gupta", "municipalId", "KNN-7781", "taxPaidUpTo", "2025-26"), null);
            doc(c, "VALUATION_REPORT", Map.of("marketValue", 8_800_000, "valuer", "Er. A Mishra"), null);
            service.addValuation(c.getId(), new CollateralService.ValuationRequest("Er. A Mishra (empanelled)", t.minusDays(25).toString(), 8_800_000d, 7_900_000d, 6_200_000d, "Land and building"), VALUER);
            // photos taken 2.3 km away, one of them reused from another file
            photos(c, List.of("PLOT_FRONT", "PLOT_SIDE", "BOUNDARY", "ACCESS_ROAD", "NEIGHBOURHOOD"), 26.4917, 80.2410, shared, 40);
        });
        log.info("Collateral AI demo cases loaded");
    }

    void step(String name, Runnable r) {
        try { r.run(); } catch (Exception e) { log.warn("Demo case {} stopped early: {}", name, e.getMessage()); }
    }

    LoanApplication app(String name, String pan, String mobile, String pin, String city, String business, double amount) {
        ApplicationRequest r = new ApplicationRequest("LAP", name, pan, mobile, null, LocalDate.now().minusYears(42), null, "21 Station Road, " + city, city, pin, business,
                BigDecimal.valueOf(9), BigDecimal.valueOf(240_000), BigDecimal.valueOf(60_000), BigDecimal.valueOf(amount), 120, "Business expansion", "50200" + mobile.substring(4),
                "HDFC0001234", true, true, true, null, "TRADING", null, null);
        LoanApplication a = apps.create(r, SALES);
        for (String t : List.of("PAN", "AADHAAR", "UDYAM"))
            docs.upload(a.getId(), t, new BytesFile(t.toLowerCase() + ".pdf", ("%PDF-1.4\n% demo " + t + "\n%%EOF").getBytes(StandardCharsets.UTF_8), "application/pdf"), SALES);
        try { process.process(a.getId(), OPS); } catch (RuntimeException e) { log.info("Credit step for {}: {}", name, e.getMessage()); }
        return a;
    }

    static CollateralService.Declare declare(String type, String desc, String owner, String address, String pin, Double lat, Double lng, String survey, String municipal, Double area) {
        return new CollateralService.Declare(type, desc, owner, address, pin, lat, lng, survey, municipal, area, type == null ? "COMPLETE" : null,
                type == null ? "SELF" : null, type == null ? "GOOD" : null, null, null, null, null, null, null, null, null, null);
    }

    static Map<String, Object> tx(String date, String type, String no, String from, String to, String linked) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("date", date); m.put("type", type); m.put("docNo", no); m.put("from", from); m.put("to", to); m.put("linkedRef", linked);
        return m;
    }

    void doc(Collateral c, String type, Map<String, Object> fields, List<Map<String, Object>> tx) {
        try {
            byte[] b = ("%PDF-1.4\n% SPECIMEN " + type + " for " + c.getColNo() + "\n%%EOF").getBytes(StandardCharsets.UTF_8);
            service.uploadDocument(c.getId(), new BytesFile(type.toLowerCase() + ".pdf", b, "application/pdf"), type, Json.write(fields), tx == null ? null : Json.write(tx), SALES);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void photos(Collateral c, List<String> labels, double lat, double lng, byte[] reuseFirst, int seed) {
        String at = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toString();
        int i = 0;
        for (String l : labels) {
            byte[] img = i == 0 && reuseFirst != null ? reuseFirst : photo(seed + i);
            try {
                service.uploadMedia(c.getId(), new BytesFile(l.toLowerCase() + ".jpg", img, "image/jpeg"), l, lat + i * 0.00005, lng, at, FIELD);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            i++;
        }
    }

    /** A synthetic "photo": coloured blocks from a seed, so each one has its own perceptual hash. */
    static byte[] photo(int seed) {
        try {
            Random r = new Random(seed * 7919L);
            BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            for (int y = 0; y < 480; y += 40)
                for (int x = 0; x < 640; x += 40) {
                    g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
                    g.fillRect(x, y, 40, 40);
                }
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
