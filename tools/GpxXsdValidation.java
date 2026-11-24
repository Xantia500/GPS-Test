package com.example.gpstest;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;

/**
 * Validation d'intégration du XML réellement produit par GpxWriter contre les
 * schémas GPX 1.0 et 1.1 téléchargés depuis les URL canoniques Topografix.
 * Classe autonome appelée par tools/validate_gpx_xsd.py (hors tests JVM usuels).
 */
public final class GpxXsdValidation {
    private int passed;
    private int failed;

    private void check(String label, boolean result) {
        if (result) {
            passed++;
            System.out.println("  OK   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }

    private static Schema loadSchema(File file) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newSchema(file);
    }

    private static void validate(Schema schema, String xml) throws Exception {
        Validator validator = schema.newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    private static String replaceOnce(String source, String from, String to) {
        int at = source.indexOf(from);
        if (at < 0 || source.indexOf(from, at + from.length()) >= 0) {
            throw new IllegalStateException("motif absent ou non unique : " + from);
        }
        return source.substring(0, at) + to + source.substring(at + from.length());
    }

    private static List<GpxWriter.TrackPoint> representativePoints() {
        List<GpxWriter.TrackPoint> points = new ArrayList<>();
        GpxWriter.TrackPoint point = new GpxWriter.TrackPoint(
                1_763_750_000_000L, 48.8566, 2.3522, 35.25);
        point.speedMps = 3.25f;
        point.courseDeg = 360.0f; // borne XSD incluse pour GPX 1.0
        point.hdop = 0.7f;
        point.accuracyM = 2.1f;
        points.add(point);
        return points;
    }

    private static String swapGpx10AccuracyBeforeMotion(String valid) {
        String ordered = "        <course>360.0</course>\n"
                + "        <speed>3.25</speed>\n"
                + "        <hdop>0.70</hdop>\n";
        String wrong = "        <hdop>0.70</hdop>\n"
                + "        <course>360.0</course>\n"
                + "        <speed>3.25</speed>\n";
        return replaceOnce(valid, ordered, wrong);
    }

    public static void main(String[] args) throws Exception {
        File schemaDir = args.length > 0 ? new File(args[0]) : new File("tools/xsd");
        File schema10File = new File(schemaDir, "gpx-1.0.xsd");
        File schema11File = new File(schemaDir, "gpx-1.1.xsd");
        if (!schema10File.isFile() || !schema11File.isFile()) {
            System.err.println("Schémas XSD absents. Exécuter d'abord tools/download_gpx_schemas.py.");
            System.exit(2);
        }

        GpxXsdValidation suite = new GpxXsdValidation();
        Schema schema10 = loadSchema(schema10File);
        Schema schema11 = loadSchema(schema11File);
        List<GpxWriter.TrackPoint> points = representativePoints();
        String valid10 = GpxWriter.build(points, "Validation GPS-Test 1.0", GpxWriter.Version.V10);
        String valid11 = GpxWriter.build(points, "Validation GPS-Test 1.1", GpxWriter.Version.V11);

        try {
            validate(schema10, valid10);
            suite.check("GpxWriter.build GPX 1.0 valide contre le XSD Topografix", true);
        } catch (Exception ex) {
            suite.check("GpxWriter.build GPX 1.0 valide contre le XSD Topografix : "
                    + ex.getMessage(), false);
        }
        try {
            validate(schema11, valid11);
            suite.check("GpxWriter.build GPX 1.1 valide contre le XSD Topografix", true);
        } catch (Exception ex) {
            suite.check("GpxWriter.build GPX 1.1 valide contre le XSD Topografix : "
                    + ex.getMessage(), false);
        }

        try {
            validate(schema10, swapGpx10AccuracyBeforeMotion(valid10));
            suite.check("le XSD GPX 1.0 rejette hdop placé avant course/speed", false);
        } catch (SAXException expected) {
            suite.check("le XSD GPX 1.0 rejette hdop placé avant course/speed", true);
        }

        String illegalDirectSpeed11 = replaceOnce(valid11,
                "<gpstest:speed>3.25</gpstest:speed>",
                "<speed>3.25</speed>");
        try {
            validate(schema11, illegalDirectSpeed11);
            suite.check("le XSD GPX 1.1 rejette speed comme enfant direct de trkpt/extensions", false);
        } catch (SAXException expected) {
            suite.check("le XSD GPX 1.1 rejette speed comme élément GPX direct non prévu", true);
        }

        System.out.println("TOTAL XSD : " + suite.passed + " réussis, "
                + suite.failed + " échoués.");
        if (suite.failed != 0) System.exit(1);
    }
}
