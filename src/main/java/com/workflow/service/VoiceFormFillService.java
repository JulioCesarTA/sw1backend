package com.workflow.service;

import com.workflow.model.FormDefinition;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VoiceFormFillService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:[\\.,]\\d+)?");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern SLASH_DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b");
    private static final Pattern TEXT_CLEANUP_PREFIX = Pattern.compile("^(es|sera|seria|pone|pon|ponle|coloca|colocale|con|valor|dice|igual a)\\s+");
    private static final Pattern SPANISH_LONG_DATE_PATTERN = Pattern.compile("\\b(\\d{1,2})\\s+de\\s+([a-záéíóú]+)\\s+de\\s+(\\d{4})\\b");

    private static final List<String> BOOLEAN_TRUE_WORDS = List.of("si", "sí", "verdadero", "true", "marcado", "marcar", "activar", "activo", "acepto");
    private static final List<String> BOOLEAN_FALSE_WORDS = List.of("no", "falso", "false", "desmarcado", "desmarcar", "desactivar", "inactivo", "rechazo");

    public Map<String, Object> parseTranscript(String transcript,
                                               FormDefinition formDefinition,
                                               Map<String, Object> currentFormData) {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> mergedFormData = new LinkedHashMap<>();
        if (currentFormData != null) {
            mergedFormData.putAll(currentFormData);
        }

        if (transcript == null || transcript.isBlank() || formDefinition == null || formDefinition.getFields() == null) {
            response.put("transcript", transcript == null ? "" : transcript);
            response.put("formData", mergedFormData);
            response.put("appliedFields", List.of());
            response.put("warnings", List.of("No se pudo interpretar el texto de voz"));
            return response;
        }

        String normalizedTranscript = normalize(transcript);
        List<FieldMarker> markers = extractMarkers(formDefinition.getFields(), normalizedTranscript);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> appliedFields = new ArrayList<>();

        if (markers.isEmpty() && formDefinition.getFields().size() == 1) {
            FormDefinition.FormField onlyField = formDefinition.getFields().get(0);
            Object value = normalizeValue(onlyField.getType(), transcript);
            if (value != null) {
                mergedFormData.put(onlyField.getName(), value);
                appliedFields.add(Map.of("field", onlyField.getName(), "value", value));
            } else {
                warnings.add("No se pudo convertir el valor para " + onlyField.getName());
            }
        } else {
            for (int index = 0; index < markers.size(); index++) {
                FieldMarker marker = markers.get(index);
                int valueStart = marker.valueStart();
                int valueEnd = index + 1 < markers.size() ? markers.get(index + 1).start() : normalizedTranscript.length();
                if (valueStart >= valueEnd) continue;

                String rawValue = transcript.substring(Math.min(valueStart, transcript.length()), Math.min(valueEnd, transcript.length())).trim();
                rawValue = cleanupRawValue(rawValue);
                if (rawValue.isBlank()) continue;

                Object normalizedValue = normalizeValue(marker.field().getType(), rawValue);
                if (normalizedValue == null) {
                    warnings.add("No se pudo convertir el valor para " + marker.field().getName());
                    continue;
                }
                mergedFormData.put(marker.field().getName(), normalizedValue);
                appliedFields.add(Map.of("field", marker.field().getName(), "value", normalizedValue));
            }
        }

        response.put("transcript", transcript);
        response.put("formData", mergedFormData);
        response.put("appliedFields", appliedFields);
        response.put("warnings", warnings);
        return response;
    }

    private List<FieldMarker> extractMarkers(List<FormDefinition.FormField> fields, String transcript) {
        List<FieldMarker> matches = new ArrayList<>();
        for (FormDefinition.FormField field : fields) {
            if (field == null || field.getName() == null || field.getName().isBlank()) continue;
            for (String alias : buildAliases(field.getName())) {
                Pattern pattern = Pattern.compile("(?<!\\p{L})(?:" + Pattern.quote(alias) + ")(?!\\p{L})");
                Matcher matcher = pattern.matcher(transcript);
                while (matcher.find()) {
                    matches.add(new FieldMarker(field, matcher.start(), matcher.end(), alias.length()));
                }
            }
        }
        matches.sort(Comparator.comparingInt(FieldMarker::start).thenComparing((FieldMarker marker) -> -marker.aliasLength()));

        List<FieldMarker> deduped = new ArrayList<>();
        int lastStart = -1;
        for (FieldMarker marker : matches) {
            if (marker.start() == lastStart) continue;
            deduped.add(marker);
            lastStart = marker.start();
        }
        return deduped;
    }

    private List<String> buildAliases(String fieldName) {
        String normalized = normalize(fieldName).replace("_", " ").trim();
        List<String> aliases = new ArrayList<>();
        if (!normalized.isBlank()) {
            aliases.add(normalized);
        }
        String compact = normalized.replace(" ", "");
        if (!compact.isBlank() && !compact.equals(normalized)) {
            aliases.add(compact);
        }
        return aliases;
    }

    private Object normalizeValue(FormDefinition.FieldType type, String rawValue) {
        if (type == null) {
            return rawValue.trim();
        }
        return switch (type) {
            case NUMBER -> parseNumber(rawValue);
            case DATE -> parseDate(rawValue);
            case EMAIL -> parseEmail(rawValue);
            case CHECKBOX -> parseCheckbox(rawValue);
            case FILE, GRID -> null;
            case TEXT -> cleanupRawValue(rawValue);
        };
    }

    private Boolean parseCheckbox(String rawValue) {
        String normalized = normalize(rawValue);
        for (String token : BOOLEAN_TRUE_WORDS) {
            if (normalized.contains(normalize(token))) {
                return true;
            }
        }
        for (String token : BOOLEAN_FALSE_WORDS) {
            if (normalized.contains(normalize(token))) {
                return false;
            }
        }
        return null;
    }

    private Object parseNumber(String rawValue) {
        Matcher matcher = NUMBER_PATTERN.matcher(rawValue);
        if (!matcher.find()) return null;
        String numeric = matcher.group().replace(",", ".");
        if (numeric.contains(".")) {
            return Double.parseDouble(numeric);
        }
        return Integer.parseInt(numeric);
    }

    private Object parseDate(String rawValue) {
        String normalized = normalize(rawValue);

        Matcher isoMatcher = ISO_DATE_PATTERN.matcher(normalized);
        if (isoMatcher.find()) {
            return String.format("%s-%s-%s", isoMatcher.group(1), isoMatcher.group(2), isoMatcher.group(3));
        }

        Matcher slashMatcher = SLASH_DATE_PATTERN.matcher(normalized);
        if (slashMatcher.find()) {
            int day = Integer.parseInt(slashMatcher.group(1));
            int month = Integer.parseInt(slashMatcher.group(2));
            int year = Integer.parseInt(slashMatcher.group(3));
            if (year < 100) year += 2000;
            return formatDate(day, month, year);
        }

        Matcher longMatcher = SPANISH_LONG_DATE_PATTERN.matcher(normalized);
        if (longMatcher.find()) {
            int day = Integer.parseInt(longMatcher.group(1));
            Integer month = monthNumber(longMatcher.group(2));
            int year = Integer.parseInt(longMatcher.group(3));
            if (month != null) {
                return formatDate(day, month, year);
            }
        }

        return null;
    }

    private String parseEmail(String rawValue) {
        String email = normalize(rawValue)
                .replace(" arroba ", "@")
                .replace(" arroba", "@")
                .replace("arroba ", "@")
                .replace(" punto ", ".")
                .replace(" punto", ".")
                .replace("punto ", ".")
                .replace(" guion ", "-")
                .replace("guion ", "-")
                .replace(" guion", "-")
                .replace(" ", "");
        return email.contains("@") ? email : null;
    }

    private String cleanupRawValue(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        value = value.replaceAll("^[,:;\\-\\s]+", "").replaceAll("[,;\\.]$", "").trim();
        Matcher matcher = TEXT_CLEANUP_PREFIX.matcher(normalize(value));
        if (matcher.find()) {
            value = value.substring(matcher.end()).trim();
        }
        return value;
    }

    private String formatDate(int day, int month, int year) {
        LocalDate date = LocalDate.of(year, month, day);
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private Integer monthNumber(String monthName) {
        return switch (normalize(monthName)) {
            case "enero", "enro" -> 1;
            case "febrero", "febreo" -> 2;
            case "marzo" -> 3;
            case "abril" -> 4;
            case "mayo" -> 5;
            case "junio" -> 6;
            case "julio" -> 7;
            case "agosto" -> 8;
            case "septiembre", "setiembre" -> 9;
            case "octubre" -> 10;
            case "noviembre" -> 11;
            case "diciembre" -> 12;
            default -> null;
        };
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    }

    private record FieldMarker(FormDefinition.FormField field, int start, int end, int aliasLength) {
        int valueStart() {
            return end;
        }
    }
}
