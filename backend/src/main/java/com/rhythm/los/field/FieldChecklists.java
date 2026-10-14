package com.rhythm.los.field;

import java.util.*;

/**
 * Product- and industry-specific field-visit checklists (FR-FV-06, FSD 11.4 data list). Yes/no checks and numeric
 * readings; the numeric ones also feed the industry scorecards. Required photos per industry.
 */
public final class FieldChecklists {
    private FieldChecklists() {}

    public record Item(String key, String label, String type, boolean required) {}  // type: YESNO, NUMBER, CHOICE:A|B
    public record Checklist(String industry, List<Item> items, List<String> photos) {}

    static final List<Item> COMMON = List.of(
            new Item("businessRunning", "Business (or residence) seen running / lived in", "YESNO", true),
            new Item("ownerMet", "Applicant met in person", "YESNO", true),
            new Item("matchesType", "Activity matches the application", "YESNO", true),
            new Item("premises", "Premises", "CHOICE:OWNED|RENTED|FAMILY", true));

    static final Map<String, List<Item>> SPECIFIC = Map.of(
            "KIRANA", List.of(new Item("stockValue", "Stock value seen (₹)", "NUMBER", true), new Item("shopSizeSqft", "Shop size (sq ft)", "NUMBER", true),
                    new Item("footfall", "Customers seen", "CHOICE:LOW|MEDIUM|HIGH", false)),
            "DAIRY", List.of(new Item("animals", "Milch animals", "NUMBER", true), new Item("milkSocietyCard", "Milk society card seen", "YESNO", true),
                    new Item("dailyMilkLitres", "Milk per day (litres)", "NUMBER", false)),
            "TRANSPORT", List.of(new Item("vehiclesOwned", "Vehicles owned", "NUMBER", true), new Item("vehicleAgeYears", "Age of the vehicle (years)", "NUMBER", true),
                    new Item("odometerKm", "Odometer (km)", "NUMBER", false)),
            "SALARIED", List.of(new Item("residenceVerified", "Residence verified with a neighbour", "YESNO", true),
                    new Item("employerConfirmed", "Employment confirmed (ID card or call)", "YESNO", false)),
            "GENERAL", List.of(new Item("employees", "People working", "NUMBER", false), new Item("machineryWorking", "Equipment or machinery working", "YESNO", false),
                    new Item("stockValue", "Stock or raw material seen (₹)", "NUMBER", false)));

    static final Map<String, List<String>> PHOTOS = Map.of(
            "KIRANA", List.of("front", "stock", "owner"),
            "DAIRY", List.of("front", "animals", "owner"),
            "TRANSPORT", List.of("vehicle", "odometer", "owner"),
            "SALARIED", List.of("residence", "owner"),
            "GENERAL", List.of("front", "inside", "owner"));

    /** Scene the AI should see in each photo kind. */
    static final Map<String, String> EXPECTED_SCENE = Map.of("stock", "SHOP", "animals", "LIVESTOCK", "vehicle", "VEHICLE",
            "odometer", "VEHICLE", "residence", "HOME");

    public static String group(String industry, String segment) {
        if (industry == null) return "SALARIED".equals(segment) ? "SALARIED" : "GENERAL";
        return switch (industry) {
            case "KIRANA", "TRADING" -> "KIRANA";
            case "DAIRY", "AGRI" -> "DAIRY";
            case "TRANSPORT" -> "TRANSPORT";
            case "SALARIED" -> "SALARIED";
            default -> "GENERAL";
        };
    }

    public static Checklist forApplication(String industry, String segment) {
        String g = group(industry, segment);
        List<Item> items = new ArrayList<>(COMMON);
        items.addAll(SPECIFIC.get(g));
        return new Checklist(g, items, PHOTOS.get(g));
    }

    public static Map<String, Checklist> all() {
        Map<String, Checklist> m = new LinkedHashMap<>();
        for (String g : List.of("KIRANA", "DAIRY", "TRANSPORT", "SALARIED", "GENERAL")) {
            List<Item> items = new ArrayList<>(COMMON);
            items.addAll(SPECIFIC.get(g));
            m.put(g, new Checklist(g, items, PHOTOS.get(g)));
        }
        return m;
    }
}
