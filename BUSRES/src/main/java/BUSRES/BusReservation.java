package BUSRES; // change to your project package

import java.sql.*;
import java.util.Scanner;

public class BusReservation {

    // update to your DB settings or use db.properties loader
    private static final String URL = "jdbc:mysql://localhost:3306/busdb?useSSL=false&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "QAZmlp123!@#";

    public static void main(String[] args) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.out.println("MySQL Driver not found. Add connector jar.");
            return;
        }

        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             Scanner sc = new Scanner(System.in)) {

            System.out.println("✅ Connected to busdb");
            boolean running = true;
            while (running) {
                showMenu();
                int choice = readInt(sc, "Enter choice: ");
                switch (choice) {
                    case 1: showBuses(conn); break;
                    case 2: viewBusDetails(conn, sc); break;
                    case 3: bookTicket(conn, sc); break;
                    case 4: cancelBooking(conn, sc); break;
                    case 5: viewBookings(conn); break;
                    case 6:
                        System.out.println("Exiting... Bye!");
                        running = false;
                        break;
                    default:
                        System.out.println("Invalid choice.");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void showMenu() {
        System.out.println("\n=== BUS RESERVATION MENU ===");
        System.out.println("1. View All Buses");
        System.out.println("2. View Bus Details / Available Seats");
        System.out.println("3. Book Ticket");
        System.out.println("4. Cancel Booking");
        System.out.println("5. View All Bookings");
        System.out.println("6. Exit");
    }

    private static int readInt(Scanner sc, String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                String s = sc.nextLine().trim();
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("Enter valid number.");
            }
        }
    }

    private static void showBuses(Connection conn) {
        String q = "SELECT bus_id, bus_no, route, available_seats, fare FROM buses";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(q)) {
            System.out.println("\nID | Bus No | Route | Available | Fare");
            System.out.println("--------------------------------------------------");
            while (rs.next()) {
                System.out.printf("%d | %s | %s | %d | ₹%.2f%n",
                        rs.getInt("bus_id"),
                        rs.getString("bus_no"),
                        rs.getString("route"),
                        rs.getInt("available_seats"),
                        rs.getDouble("fare"));
            }
        } catch (SQLException e) {
            System.out.println("Error fetching buses: " + e.getMessage());
        }
    }

    private static void viewBusDetails(Connection conn, Scanner sc) {
        int id = readInt(sc, "Enter Bus ID: ");
        String q = "SELECT * FROM buses WHERE bus_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("Bus No: " + rs.getString("bus_no"));
                    System.out.println("Route : " + rs.getString("route"));
                    System.out.println("Total Seats : " + rs.getInt("total_seats"));
                    System.out.println("Available Seats : " + rs.getInt("available_seats"));
                    System.out.println("Fare per seat : ₹" + rs.getDouble("fare"));
                } else {
                    System.out.println("Bus not found.");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    // Booking with transaction
    private static void bookTicket(Connection conn, Scanner sc) {
        int busId = readInt(sc, "Enter Bus ID to book: ");
        String passenger = "";
        while (passenger.isEmpty()) {
            System.out.print("Enter Passenger Name: ");
            passenger = sc.nextLine().trim();
        }
        System.out.print("Enter Phone (optional): ");
        String phone = sc.nextLine().trim();
        int seats = readInt(sc, "Enter number of seats to book: ");

        String selectSql = "SELECT available_seats, fare FROM buses WHERE bus_id = ? FOR UPDATE";
        String insertSql = "INSERT INTO bookings (bus_id, passenger_name, passenger_phone, seats_booked, amount_paid) VALUES (?, ?, ?, ?, ?)";
        String updateSql = "UPDATE buses SET available_seats = available_seats - ? WHERE bus_id = ?";

        try {
            conn.setAutoCommit(false);

            try (PreparedStatement psSelect = conn.prepareStatement(selectSql)) {
                psSelect.setInt(1, busId);
                try (ResultSet rs = psSelect.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println("Bus not found.");
                        conn.rollback();
                        conn.setAutoCommit(true);
                        return;
                    }
                    int avail = rs.getInt("available_seats");
                    double fare = rs.getDouble("fare");
                    if (seats <= 0) {
                        System.out.println("Seats must be positive.");
                        conn.rollback();
                        conn.setAutoCommit(true);
                        return;
                    }
                    if (seats > avail) {
                        System.out.println("Not enough seats. Available: " + avail);
                        conn.rollback();
                        conn.setAutoCommit(true);
                        return;
                    }

                    double amount = seats * fare;

                    try (PreparedStatement psInsert = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
                         PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {

                        psInsert.setInt(1, busId);
                        psInsert.setString(2, passenger);
                        psInsert.setString(3, phone.isEmpty() ? null : phone);
                        psInsert.setInt(4, seats);
                        psInsert.setDouble(5, amount);
                        psInsert.executeUpdate();

                        psUpdate.setInt(1, seats);
                        psUpdate.setInt(2, busId);
                        psUpdate.executeUpdate();

                        conn.commit();
                        System.out.printf("Booking successful! Seats: %d, Amount: ₹%.2f%n", seats, amount);

                        try (ResultSet gen = psInsert.getGeneratedKeys()) {
                            if (gen.next()) {
                                System.out.println("Booking ID: " + gen.getInt(1));
                            }
                        }
                    }
                }
            }

            conn.setAutoCommit(true);
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ex) {}
            System.out.println("Booking failed: " + e.getMessage());
            try { conn.setAutoCommit(true); } catch (SQLException ex) {}
        }
    }

    private static void cancelBooking(Connection conn, Scanner sc) {
        int bid = readInt(sc, "Enter Booking ID to cancel: ");
        String select = "SELECT bus_id, seats_booked FROM bookings WHERE booking_id = ?";
        String delete = "DELETE FROM bookings WHERE booking_id = ?";
        try {
            conn.setAutoCommit(false);
            int busId = -1;
            int seats = 0;
            try (PreparedStatement ps = conn.prepareStatement(select)) {
                ps.setInt(1, bid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println("Booking not found.");
                        conn.rollback();
                        conn.setAutoCommit(true);
                        return;
                    }
                    busId = rs.getInt("bus_id");
                    seats = rs.getInt("seats_booked");
                }
            }

            try (PreparedStatement psDel = conn.prepareStatement(delete);
                 PreparedStatement psUpd = conn.prepareStatement("UPDATE buses SET available_seats = available_seats + ? WHERE bus_id = ?")) {
                psDel.setInt(1, bid);
                psDel.executeUpdate();

                psUpd.setInt(1, seats);
                psUpd.setInt(2, busId);
                psUpd.executeUpdate();

                conn.commit();
                System.out.println("Booking cancelled. Seats released: " + seats);
            }

            conn.setAutoCommit(true);
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ex) {}
            System.out.println("Cancel failed: " + e.getMessage());
            try { conn.setAutoCommit(true); } catch (SQLException ex) {}
        }
    }

    private static void viewBookings(Connection conn) {
        String q = "SELECT b.booking_id, b.passenger_name, b.passenger_phone, b.seats_booked, b.amount_paid, b.booking_time, bus.bus_no, bus.route " +
                "FROM bookings b JOIN buses bus ON b.bus_id = bus.bus_id ORDER BY b.booking_time DESC";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(q)) {
            System.out.println("\nID | Passenger | Phone | Seats | Amount | Time | BusNo | Route");
            System.out.println("---------------------------------------------------------------------");
            while (rs.next()) {
                System.out.printf("%d | %s | %s | %d | ₹%.2f | %s | %s | %s%n",
                        rs.getInt("booking_id"),
                        rs.getString("passenger_name"),
                        rs.getString("passenger_phone"),
                        rs.getInt("seats_booked"),
                        rs.getDouble("amount_paid"),
                        rs.getTimestamp("booking_time"),
                        rs.getString("bus_no"),
                        rs.getString("route"));
            }
        } catch (SQLException e) {
            System.out.println("Error fetching bookings: " + e.getMessage());
        }
    }
}
