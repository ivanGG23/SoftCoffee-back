package softcoffee;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConexionEC2 {
    public static Connection obtenerConexion() throws SQLException {
        String url = "jdbc:mysql://34.233.15.236:3306/SoftCoffee";
        String usuario = "adminpva"; // el usuario que creaste en MySQL
        String clave = "Ivan0000."; // contraseña segura

        return DriverManager.getConnection(url, usuario, clave);
    }
}