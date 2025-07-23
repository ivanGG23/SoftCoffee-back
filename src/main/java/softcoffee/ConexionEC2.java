package softcoffee;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConexionEC2 {
    public static Connection obtenerConexion() throws SQLException {
        String url = "jdbc:mysql://172.31.20.101:3306/SoftCoffee";
        String usuario = "adminpva"; // el usuario que creaste en MySQL
        String clave = "Ivan0000."; // contraseña segura

        return DriverManager.getConnection(url, usuario, clave);
    }
}