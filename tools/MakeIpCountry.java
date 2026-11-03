import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Converts DB-IP Country Lite CSV.gz to sorted 10-byte IPv4 range records for the Android asset. */
class MakeIpCountry {
    private static long ipv4(String text) {
        String[] parts = text.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException(text);
        long value = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException(text);
            value = (value << 8) | octet;
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java tools/MakeIpCountry.java input.csv.gz output.bin");
        int count = 0;
        long previousEnd = -1;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                     new GZIPInputStream(Files.newInputStream(Path.of(args[0]))), StandardCharsets.UTF_8));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[1]))))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split(",", -1);
                if (fields.length != 3) throw new IllegalArgumentException("Unexpected CSV row");
                if (fields[0].indexOf(':') >= 0) continue; // WAN displays IPv4 only
                long start = ipv4(fields[0]), end = ipv4(fields[1]);
                String country = fields[2];
                if (start <= previousEnd || end < start || !country.matches("[A-Z]{2}"))
                    throw new IllegalArgumentException("Unsorted or invalid IPv4 range: " + line);
                out.writeInt((int) start);
                out.writeInt((int) end);
                out.writeByte(country.charAt(0));
                out.writeByte(country.charAt(1));
                previousEnd = end;
                count++;
            }
        }
        System.out.println("Wrote " + count + " IPv4 ranges");
    }
}
