package softcoffee;

import softcoffee.models.MenuRequest;
import io.javalin.Javalin;
import static io.javalin.apibuilder.ApiBuilder.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SoftCoffee {
    public static void main(String[] args) { 
        Javalin app = Javalin.create(config -> { 
            config.plugins.enableCors(cors -> { 
                cors.add(it -> { 
                    it.anyHost(); 
                }); 
            }); 
        }).start("0.0.0.0", 7000);

        app.routes(() -> {

            // LOGIN
            post("/login", ctx -> {
                JSONObject datos = new JSONObject(ctx.body());

                String nombre = datos.getString("nombre");
                String contraseña = datos.getString("contraseña");
                String rolEsperado = datos.getString("rol");

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement(
                            "SELECT rol, estado FROM USUARIOS_EMPLEADO WHERE nombre = ? AND contraseña = ?");
                    stmt.setString(1, nombre);
                    stmt.setString(2, contraseña);

                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        String rolUsuario = rs.getString("rol").trim().toLowerCase();
                        String estadoUsuario = rs.getString("estado").trim().toLowerCase();

                        if (!estadoUsuario.equals("activo")) {
                            ctx.status(403).result("Usuario inactivo. Acceso denegado.");
                        } else if (rolUsuario.equals(rolEsperado.toLowerCase())) {
                            ctx.status(200).result("Acceso concedido");
                        } else {
                            ctx.status(403).result("Acceso denegado para este rol");
                        }
                    } else {
                        ctx.status(401).result("Nombre o contraseña incorrectos");
                    }
                } catch (SQLException e) {
                    ctx.status(500).result("Error interno: " + e.getMessage());
                }
            });

            // TABLA USUARIOS
            get("/usuarios", ctx -> {
                JSONArray usuariosArray = new JSONArray();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = "SELECT ID_usuario, nombre, apellidos, telefono, direccion, rol FROM USUARIOS_EMPLEADO WHERE estado = 'activo'";
                    PreparedStatement stmt = con.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        JSONObject usuario = new JSONObject();
                        usuario.put("id", rs.getInt("ID_usuario"));
                        usuario.put("nombre", rs.getString("nombre"));
                        usuario.put("apellidos", rs.getString("apellidos"));
                        usuario.put("telefono", rs.getLong("telefono"));
                        usuario.put("direccion", rs.getString("direccion"));
                        usuario.put("rol", rs.getString("rol"));

                        usuariosArray.put(usuario);
                    }

                    ctx.status(200).result(usuariosArray.toString());
                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener usuarios: " + e.getMessage());
                }
            });

            // REGISTRAR USUARIO NUEVO
            post("/usuarios", ctx -> {
                try {
                    JSONObject datos = new JSONObject(ctx.body());

                    System.out.println("Datos recibidos: " + datos);

                    String nombre = datos.optString("nombre", "").trim();
                    String apellidos = datos.optString("apellidos", "").trim();
                    long telefono = datos.optLong("telefono", -1);
                    String direccion = datos.optString("direccion", "").trim();
                    String contraseña = datos.optString("contraseña", "").trim();
                    String rol = datos.optString("rol", "").toLowerCase();

                    if (nombre.isEmpty() || apellidos.isEmpty() || telefono <= 0 || direccion.isEmpty()
                            || contraseña.isEmpty() ||
                            !(rol.equals("cajero") || rol.equals("barista") || rol.equals("administrador"))) {
                        ctx.status(400).result("Datos inválidos");
                        return;
                    }

                    try (Connection con = ConexionEC2.obtenerConexion()) {
                        con.setAutoCommit(false);

                        String sqlEmpleado = "INSERT INTO USUARIOS_EMPLEADO (nombre, apellidos, telefono, direccion, contraseña, rol) VALUES (?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement stmtEmpleado = con.prepareStatement(sqlEmpleado)) {
                            stmtEmpleado.setString(1, nombre);
                            stmtEmpleado.setString(2, apellidos);
                            stmtEmpleado.setLong(3, telefono);
                            stmtEmpleado.setString(4, direccion);
                            stmtEmpleado.setString(5, contraseña);
                            stmtEmpleado.setString(6, rol);
                            stmtEmpleado.executeUpdate();
                        }

                        if (rol.equals("administrador")) {
                            String sqlAdmin = "INSERT INTO ADMINISTRADOR (nombre, apellidos, telefono, direccion, contraseña) VALUES (?, ?, ?, ?, ?)";
                            try (PreparedStatement stmtAdmin = con.prepareStatement(sqlAdmin)) {
                                stmtAdmin.setString(1, nombre);
                                stmtAdmin.setString(2, apellidos);
                                stmtAdmin.setLong(3, telefono);
                                stmtAdmin.setString(4, direccion);
                                stmtAdmin.setInt(5, Integer.parseInt(contraseña));
                                stmtAdmin.executeUpdate();
                            }
                        }

                        con.commit();
                        ctx.status(201).result("Usuario creado exitosamente");
                    } catch (Exception e) {
                        e.printStackTrace();
                        ctx.status(500).result("Error al crear usuario: " + e.getMessage());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    ctx.status(500).result("Error al procesar solicitud: " + e.getMessage());
                }
            });

            // DESACTIVAR USUARIO
            delete("/usuarios/{id}", ctx -> {
                String idParam = ctx.pathParam("id");

                if (idParam == null || idParam.isEmpty()) {
                    ctx.status(400).result("ID de usuario no proporcionado.");
                    return;
                }

                int idUsuario;
                try {
                    idUsuario = Integer.parseInt(idParam);
                } catch (NumberFormatException e) {
                    ctx.status(400).result("ID de usuario inválido.");
                    return;
                }

                System.out.println("Recibida petición DELETE para ID: " + idUsuario);

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String verificarSql = "SELECT estado FROM USUARIOS_EMPLEADO WHERE ID_usuario = ?";
                    PreparedStatement verificarStmt = con.prepareStatement(verificarSql);
                    verificarStmt.setInt(1, idUsuario);
                    ResultSet rs = verificarStmt.executeQuery();

                    if (!rs.next()) {
                        ctx.status(404).result("Usuario no encontrado.");
                        return;
                    }

                    String estadoActual = rs.getString("estado").trim();
                    if ("inactivo".equalsIgnoreCase(estadoActual)) {
                        ctx.status(400).result("El usuario ya está inactivo.");
                        return;
                    }

                    String actualizarSql = "UPDATE USUARIOS_EMPLEADO SET estado = 'inactivo' WHERE ID_usuario = ?";
                    PreparedStatement actualizarStmt = con.prepareStatement(actualizarSql);
                    actualizarStmt.setInt(1, idUsuario);
                    int filasAfectadas = actualizarStmt.executeUpdate();

                    if (filasAfectadas > 0) {
                        ctx.status(200).result("Usuario desactivado correctamente.");
                    } else {
                        ctx.status(500).result("No se pudo desactivar el usuario.");
                    }
                } catch (SQLException e) {
                    ctx.status(500).result("Error al desactivar usuario: " + e.getMessage());
                }
            });

            // ACTUALIZAR DATOS
            put("/usuarios/{id}", ctx -> {
                String idParam = ctx.pathParam("id");

                if (idParam == null || idParam.isEmpty()) {
                    ctx.status(400).result("ID de usuario no proporcionado.");
                    return;
                }

                int idUsuario;
                try {
                    idUsuario = Integer.parseInt(idParam);
                } catch (NumberFormatException e) {
                    ctx.status(400).result("ID de usuario inválido.");
                    return;
                }

                JSONObject datos;
                try {
                    datos = new JSONObject(ctx.body());
                } catch (Exception e) {
                    ctx.status(400).result("Formato JSON inválido.");
                    return;
                }

                String nombre = datos.optString("nombre", "").trim();
                String apellidos = datos.optString("apellidos", "").trim();
                long telefono = datos.optLong("telefono", -1);
                String direccion = datos.optString("direccion", "").trim();
                String contraseña = datos.optString("contraseña", "").trim();
                String rol = datos.optString("rol", "").toLowerCase();

                if (nombre.isEmpty() || apellidos.isEmpty() || telefono <= 0 || direccion.isEmpty()
                        || contraseña.isEmpty() ||
                        !(rol.equals("cajero") || rol.equals("barista") || rol.equals("administrador"))) {
                    ctx.status(400).result("Datos inválidos.");
                    return;
                }

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    con.setAutoCommit(false);

                    String verificarSql = "SELECT rol, estado FROM USUARIOS_EMPLEADO WHERE ID_usuario = ?";
                    PreparedStatement verificarStmt = con.prepareStatement(verificarSql);
                    verificarStmt.setInt(1, idUsuario);
                    ResultSet rs = verificarStmt.executeQuery();

                    if (!rs.next()) {
                        ctx.status(404).result("Usuario no encontrado.");
                        return;
                    }

                    String estadoActual = rs.getString("estado").trim();
                    String rolAnterior = rs.getString("rol").trim().toLowerCase();

                    if ("inactivo".equalsIgnoreCase(estadoActual)) {
                        ctx.status(400).result("El usuario está inactivo y no puede ser editado.");
                        return;
                    }

                    String actualizarSql = """
                                UPDATE USUARIOS_EMPLEADO
                                SET nombre = ?, apellidos = ?, telefono = ?, direccion = ?, contraseña = ?, rol = ?
                                WHERE ID_usuario = ?
                            """;
                    PreparedStatement actualizarStmt = con.prepareStatement(actualizarSql);
                    actualizarStmt.setString(1, nombre);
                    actualizarStmt.setString(2, apellidos);
                    actualizarStmt.setLong(3, telefono);
                    actualizarStmt.setString(4, direccion);
                    actualizarStmt.setString(5, contraseña);
                    actualizarStmt.setString(6, rol);
                    actualizarStmt.setInt(7, idUsuario);
                    actualizarStmt.executeUpdate();

                    if (!rol.equals("administrador") && rolAnterior.equals("administrador")) {
                        String eliminarAdminSql = "DELETE FROM ADMINISTRADOR WHERE nombre = ? AND apellidos = ?";
                        PreparedStatement eliminarAdminStmt = con.prepareStatement(eliminarAdminSql);
                        eliminarAdminStmt.setString(1, nombre);
                        eliminarAdminStmt.setString(2, apellidos);
                        eliminarAdminStmt.executeUpdate();
                    }

                    if (rol.equals("administrador")) {
                        String actualizarAdminSql = """
                                    UPDATE ADMINISTRADOR
                                    SET nombre = ?, apellidos = ?, telefono = ?, direccion = ?, contraseña = ?
                                    WHERE nombre = ? AND apellidos = ?
                                """;
                        PreparedStatement actualizarAdminStmt = con.prepareStatement(actualizarAdminSql);
                        actualizarAdminStmt.setString(1, nombre);
                        actualizarAdminStmt.setString(2, apellidos);
                        actualizarAdminStmt.setLong(3, telefono);
                        actualizarAdminStmt.setString(4, direccion);
                        actualizarAdminStmt.setInt(5, Integer.parseInt(contraseña));
                        actualizarAdminStmt.setString(6, nombre);
                        actualizarAdminStmt.setString(7, apellidos);
                        actualizarAdminStmt.executeUpdate();
                    }

                    con.commit();
                    ctx.status(200).result("Usuario actualizado correctamente.");
                } catch (SQLException e) {
                    ctx.status(500).result("Error al actualizar usuario: " + e.getMessage());
                } catch (Exception e) {
                    ctx.status(500).result("Error inesperado: " + e.getMessage());
                }
            });

            // TABLA INVENTARIO
            get("/inventario", ctx -> {
                JSONArray productosArray = new JSONArray();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = """
                                SELECT Codigo_producto, Producto, Precio_compra, Cantidad, Fecha_caducidad
                                FROM INVENTARIO
                                WHERE estado = 'existente'
                            """;
                    PreparedStatement stmt = con.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        JSONObject producto = new JSONObject();
                        producto.put("id", rs.getInt("Codigo_producto"));
                        producto.put("nombre", rs.getString("Producto"));
                        producto.put("precio", rs.getDouble("Precio_compra"));
                        producto.put("cantidad", rs.getInt("Cantidad"));
                        producto.put("fecha", rs.getString("Fecha_caducidad"));

                        productosArray.put(producto);
                    }

                    ctx.status(200).result(productosArray.toString());
                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener inventario: " + e.getMessage());
                }
            });

            // TABLA INSUMOS
            get("/insumos", ctx -> {
                List<Map<String, Object>> insumos = new ArrayList<>();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = """
                                SELECT I.Codigo_insumo, I.unidad_medida, I.nombre_insumo, I.presentacion, I.contenido,
                                       P.Producto AS nombre_producto
                                FROM INSUMO I
                                LEFT JOIN INVENTARIO P ON I.Codigo_producto = P.Codigo_producto
                                WHERE I.estado = 'existente'
                            """;

                    PreparedStatement stmt = con.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        Map<String, Object> insumo = new HashMap<>();
                        insumo.put("codigo", rs.getInt("Codigo_insumo"));
                        insumo.put("unidad", rs.getString("unidad_medida"));
                        insumo.put("nombre", rs.getString("nombre_insumo"));
                        insumo.put("presentacion", rs.getString("presentacion"));
                        insumo.put("contenido", rs.getInt("contenido"));

                        String producto = rs.getString("nombre_producto");
                        if (producto != null) {
                            insumo.put("producto", producto);
                        }

                        insumos.add(insumo);
                    }

                    ctx.json(insumos);
                } catch (SQLException e) {
                    e.printStackTrace();
                    ctx.status(500).result("Error al obtener insumos: " + e.getMessage());
                }
            });

            // TABLA DE INCATIVOS
            get("/inventario-inactivo", ctx -> {
                JSONArray productosArray = new JSONArray();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = """
                                SELECT Codigo_producto, Producto, Precio_compra, Cantidad, Fecha_caducidad
                                FROM INVENTARIO
                                WHERE estado = 'agotado'
                            """;

                    PreparedStatement stmt = con.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        JSONObject producto = new JSONObject();
                        producto.put("id", rs.getInt("Codigo_producto"));
                        producto.put("nombre", rs.getString("Producto"));
                        producto.put("precio", rs.getDouble("Precio_compra"));
                        producto.put("cantidad", rs.getInt("Cantidad"));
                        producto.put("fecha", rs.getString("Fecha_caducidad"));
                        productosArray.put(producto);
                    }

                    ctx.status(200).result(productosArray.toString());
                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener productos inactivos: " + e.getMessage());
                }
            });

            // AGREGAR PRODUCTOS AL INVENTARIO
            post("/inventario", ctx -> {
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    JSONObject body = new JSONObject(ctx.body());
                    String sql = "INSERT INTO INVENTARIO (Codigo_producto, Producto, Precio_compra, Cantidad, Fecha_caducidad) VALUES (?, ?, ?, ?, ?)";

                    PreparedStatement stmt = con.prepareStatement(sql);
                    stmt.setInt(1, body.getInt("id"));
                    stmt.setString(2, body.getString("nombre"));
                    stmt.setDouble(3, body.getDouble("precio"));
                    stmt.setInt(4, body.getInt("cantidad"));
                    stmt.setString(5, body.getString("fecha"));

                    int filas = stmt.executeUpdate();
                    if (filas > 0) {
                        ctx.status(201).result("Producto agregado correctamente.");
                    } else {
                        ctx.status(400).result("No se pudo agregar el producto.");
                    }
                } catch (Exception e) {
                    ctx.status(500).result("Error: " + e.getMessage());
                }
            });

            // AGREGAR INSUMOS
            post("/insumos", ctx -> {
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    JSONObject body = new JSONObject(ctx.body());
                    String sql = "INSERT INTO INSUMO (Codigo_insumo, Codigo_producto, unidad_medida, nombre_insumo, presentacion, contenido) VALUES (?, ?, ?, ?, ?, ?)";

                    PreparedStatement stmt = con.prepareStatement(sql);
                    stmt.setInt(1, body.getInt("codigo"));
                    stmt.setInt(2, body.getInt("idProducto"));
                    stmt.setString(3, body.getString("unidad"));
                    stmt.setString(4, body.getString("nombre"));
                    stmt.setString(5, body.getString("presentacion"));
                    stmt.setInt(6, body.getInt("contenido"));

                    int filas = stmt.executeUpdate();
                    if (filas > 0) {
                        ctx.status(201).result("Insumo agregado correctamente.");
                    } else {
                        ctx.status(400).result("No se pudo agregar el insumo.");
                    }
                } catch (Exception e) {
                    ctx.status(500).result("Error: " + e.getMessage());
                }
            });

            // EDITAR INVENTARIO
            put("/inventario/{id}", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));
                JSONObject body = new JSONObject(ctx.body());
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement("""
                                UPDATE INVENTARIO SET Producto = ?, Precio_compra = ?, Cantidad = ?, Fecha_caducidad = ?
                                WHERE Codigo_producto = ?
                            """);
                    stmt.setString(1, body.getString("nombre"));
                    stmt.setDouble(2, body.getDouble("precio"));
                    stmt.setInt(3, body.getInt("cantidad"));
                    stmt.setString(4, body.getString("fecha"));
                    stmt.setInt(5, id);
                    ctx.status(stmt.executeUpdate() > 0 ? 200 : 404).result("Producto actualizado");
                }
            });

            // EDITAR INSUMO
            put("/insumos/{codigo}", ctx -> {
                int codigo = Integer.parseInt(ctx.pathParam("codigo"));
                JSONObject body = new JSONObject(ctx.body());
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement("""
                                UPDATE INSUMO SET unidad_medida = ?, nombre_insumo = ?, presentacion = ?, contenido = ?
                                WHERE Codigo_insumo = ?
                            """);
                    stmt.setString(1, body.getString("unidad"));
                    stmt.setString(2, body.getString("nombre"));
                    stmt.setString(3, body.getString("presentacion"));
                    stmt.setInt(4, body.getInt("contenido"));
                    stmt.setInt(5, codigo);
                    ctx.status(stmt.executeUpdate() > 0 ? 200 : 404).result("Insumo actualizado");
                }
            });

            // ELIMINAR INVENTARIO
            put("/inventario/{id}/estado", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    con.setAutoCommit(false);

                    PreparedStatement stmtProd = con.prepareStatement(
                            "UPDATE INVENTARIO SET estado = 'agotado' WHERE Codigo_producto = ?");
                    stmtProd.setInt(1, id);
                    int actualizadosProd = stmtProd.executeUpdate();

                    PreparedStatement stmtInsumos = con.prepareStatement(
                            "UPDATE INSUMO SET estado = 'agotado' WHERE Codigo_producto = ?");
                    stmtInsumos.setInt(1, id);
                    int actualizadosInsumos = stmtInsumos.executeUpdate();

                    con.commit();

                    String mensaje = "Producto agotado y " + actualizadosInsumos + " insumos también.";
                    ctx.status(actualizadosProd > 0 ? 200 : 404).result(mensaje);

                } catch (SQLException e) {
                    ctx.status(500).result("Error al actualizar estado: " + e.getMessage());
                }
            });

            // ELIMINAR INSUMO
            put("/insumos/{codigo}/estado", ctx -> {
                int codigo = Integer.parseInt(ctx.pathParam("codigo"));
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con
                            .prepareStatement("UPDATE INSUMO SET estado = 'agotado' WHERE Codigo_insumo = ?");
                    stmt.setInt(1, codigo);
                    ctx.status(stmt.executeUpdate() > 0 ? 200 : 404).result("Estado actualizado a agotado");
                }
            });

            //
            put("/inventario/{id}/reactivar", ctx -> {
                int id = Integer.parseInt(ctx.pathParam("id"));

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmtProd = con.prepareStatement(
                            "UPDATE INVENTARIO SET estado = 'existente' WHERE Codigo_producto = ?");
                    stmtProd.setInt(1, id);
                    int actualizado = stmtProd.executeUpdate();

                    ctx.status(actualizado > 0 ? 200 : 404).result("Producto reactivado correctamente.");
                } catch (SQLException e) {
                    ctx.status(500).result("Error al reactivar producto: " + e.getMessage());
                }
            });

            //
            get("/insumos-inactivos/{idProducto}", ctx -> {
                int idProducto = Integer.parseInt(ctx.pathParam("idProducto"));
                JSONArray insumosArray = new JSONArray();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = """
                                SELECT Codigo_insumo, unidad_medida, nombre_insumo, presentacion, contenido
                                FROM INSUMO
                                WHERE Codigo_producto = ? AND estado = 'agotado'
                            """;

                    PreparedStatement stmt = con.prepareStatement(sql);
                    stmt.setInt(1, idProducto);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        JSONObject insumo = new JSONObject();
                        insumo.put("codigo", rs.getInt("Codigo_insumo"));
                        insumo.put("unidad", rs.getString("unidad_medida"));
                        insumo.put("nombre", rs.getString("nombre_insumo"));
                        insumo.put("presentacion", rs.getString("presentacion"));
                        insumo.put("contenido", rs.getInt("contenido"));
                        insumosArray.put(insumo);
                    }

                    ctx.status(200).result(insumosArray.toString());
                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener insumos inactivos: " + e.getMessage());
                }
            });

            //
            put("/insumo/{codigo}/reactivar", ctx -> {
                int codigo = Integer.parseInt(ctx.pathParam("codigo"));

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement(
                            "UPDATE INSUMO SET estado = 'existente' WHERE Codigo_insumo = ?");
                    stmt.setInt(1, codigo);
                    int actualizado = stmt.executeUpdate();

                    ctx.status(actualizado > 0 ? 200 : 404).result("Insumo reactivado correctamente.");
                } catch (SQLException e) {
                    ctx.status(500).result("Error al reactivar insumo: " + e.getMessage());
                }
            });

            // CATEGORIAS
            get("/categorias", ctx -> {
                List<Map<String, Object>> categorias = new ArrayList<>();

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement(
                            "SELECT Identificador_categoria, Nombre_categoria FROM CATEGORIAS");

                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> cat = new HashMap<>();
                        cat.put("id", rs.getInt("Identificador_categoria"));
                        cat.put("nombre", rs.getString("Nombre_categoria"));
                        categorias.add(cat);
                    }

                    ctx.json(categorias);
                } catch (SQLException e) {
                    e.printStackTrace();
                    ctx.status(500).result("Error al obtener categorías: " + e.getMessage());
                }
            });

            //
            get("/productos", ctx -> {
                try (Connection con = ConexionEC2.obtenerConexion()) {
                    String sql = """
                                SELECT
                                    m.ID_menu,
                                    m.nombre_producto,
                                    m.precio_venta,
                                    m.Descripcion,
                                    m.img_url,
                                    c.Nombre_categoria AS categoria,
                                    i.Codigo_insumo,
                                    i.nombre_insumo AS insumo_nombre
                                FROM MENU m
                                JOIN CATEGORIAS c ON m.Identificador_categoria = c.Identificador_categoria
                                LEFT JOIN CONTENIDO co ON m.ID_menu = co.ID_menu
                                LEFT JOIN INSUMO i ON co.Codigo_insumo = i.Codigo_insumo
                                ORDER BY m.ID_menu
                            """;

                    PreparedStatement stmt = con.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery();

                    // Mapear los productos agrupando sus insumos
                    Map<Integer, JSONObject> productosMap = new HashMap<>();

                    while (rs.next()) {
                        int idMenu = rs.getInt("ID_menu");

                        JSONObject producto = productosMap.getOrDefault(idMenu, new JSONObject());
                        if (!producto.has("id")) {
                            producto.put("id", idMenu);
                            producto.put("nombre", rs.getString("nombre_producto"));
                            producto.put("precio", rs.getDouble("precio_venta"));
                            producto.put("descripcion", rs.getString("Descripcion"));
                            producto.put("categoria", rs.getString("categoria").toLowerCase());
                            String imagenUrl = rs.getString("img_url");
                            producto.put("img_url",
                                    imagenUrl != null && !imagenUrl.isEmpty() ? imagenUrl : "img/default.jpg");

                            producto.put("insumos", new JSONArray());
                            productosMap.put(idMenu, producto);
                        }

                        String insumoNombre = rs.getString("insumo_nombre");
                        if (insumoNombre != null && !insumoNombre.isEmpty()) {
                            producto.getJSONArray("insumos").put(insumoNombre);
                        }
                    }

                    JSONArray resultado = new JSONArray(productosMap.values());
                    ctx.status(200).result(resultado.toString());
                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener productos: " + e.getMessage());
                }
            });

            //
            get("/productos/categoria/{nombre}", ctx -> {
                String categoriaBuscada = ctx.pathParam("nombre").toLowerCase();

                // Reutiliza la lógica actual, pero filtra con WHERE
                String sql = """
                            SELECT
                                m.ID_menu,
                                m.nombre_producto,
                                m.precio_venta,
                                m.Descripcion,
                                m.img_url,
                                c.Nombre_categoria AS categoria,
                                i.Codigo_insumo,
                                i.nombre_insumo AS insumo_nombre
                            FROM MENU m
                            JOIN CATEGORIAS c ON m.Identificador_categoria = c.Identificador_categoria
                            LEFT JOIN CONTENIDO co ON m.ID_menu = co.ID_menu
                            LEFT JOIN INSUMO i ON co.Codigo_insumo = i.Codigo_insumo
                            WHERE LOWER(c.Nombre_categoria) = ?
                            ORDER BY m.ID_menu
                        """;

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    PreparedStatement stmt = con.prepareStatement(sql);
                    stmt.setString(1, categoriaBuscada);
                    ResultSet rs = stmt.executeQuery();

                    Map<Integer, JSONObject> productosMap = new HashMap<>();

                    while (rs.next()) {
                        int idMenu = rs.getInt("ID_menu");

                        JSONObject producto = productosMap.getOrDefault(idMenu, new JSONObject());
                        if (!producto.has("id")) {
                            producto.put("id", idMenu);
                            producto.put("nombre", rs.getString("nombre_producto"));
                            producto.put("precio", rs.getDouble("precio_venta"));
                            producto.put("descripcion", rs.getString("Descripcion"));
                            producto.put("categoria", rs.getString("categoria").toLowerCase());
                            String imagenUrl = rs.getString("img_url");
                            producto.put("img_url",
                                    imagenUrl != null && !imagenUrl.isEmpty() ? imagenUrl : "img/default.jpg");
                            producto.put("insumos", new JSONArray());
                            productosMap.put(idMenu, producto);
                        }

                        String insumoNombre = rs.getString("insumo_nombre");
                        if (insumoNombre != null && !insumoNombre.isEmpty()) {
                            producto.getJSONArray("insumos").put(insumoNombre);
                        }
                    }

                    JSONArray resultado = new JSONArray(productosMap.values());
                    ctx.status(200).result(resultado.toString());

                } catch (SQLException e) {
                    ctx.status(500).result("Error al obtener productos: " + e.getMessage());
                }
            });

            //
            post("/menu", ctx -> {
                MenuRequest datos = ctx.bodyAsClass(MenuRequest.class);

                try (Connection con = ConexionEC2.obtenerConexion()) {
                    con.setAutoCommit(false);

                    // Insertar en MENU
                    PreparedStatement stmtMenu = con.prepareStatement(
                            "INSERT INTO MENU (Identificador_categoria, nombre_producto, precio_venta, Descripcion) VALUES (?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                    stmtMenu.setInt(1, datos.categoriaId);
                    stmtMenu.setString(2, datos.nombre);
                    stmtMenu.setBigDecimal(3, datos.precio);
                    stmtMenu.setString(4, datos.descripcion);
                    stmtMenu.executeUpdate();

                    ResultSet rs = stmtMenu.getGeneratedKeys();
                    if (rs.next()) {
                        int nuevoIdMenu = rs.getInt(1);

                        PreparedStatement buscarInsumo = con
                                .prepareStatement("SELECT Codigo_insumo FROM INSUMO WHERE nombre_insumo = ?");
                        PreparedStatement stmtContenido = con
                                .prepareStatement("INSERT INTO CONTENIDO (ID_menu, Codigo_insumo) VALUES (?, ?)");

                        for (String nombreInsumo : datos.insumos) {
                            buscarInsumo.setString(1, nombreInsumo);
                            ResultSet resInsumo = buscarInsumo.executeQuery();
                            if (resInsumo.next()) {
                                int codigo = resInsumo.getInt("Codigo_insumo");
                                stmtContenido.setInt(1, nuevoIdMenu);
                                stmtContenido.setInt(2, codigo);
                                stmtContenido.executeUpdate();
                            }
                        }
                    }

                    con.commit();
                    ctx.status(201).result("Producto registrado correctamente");
                } catch (Exception e) {
                    e.printStackTrace();
                    ctx.status(500).result("Error al registrar producto: " + e.getMessage());
                }
            });

            //
            path("menu", () -> {
                put("{id}", ctx -> {
                    MenuRequest datos = ctx.bodyAsClass(MenuRequest.class);
                    int idMenu = Integer.parseInt(ctx.pathParam("id"));

                    try (Connection con = ConexionEC2.obtenerConexion()) {
                        con.setAutoCommit(false);

                        PreparedStatement stmtMenu = con.prepareStatement(
                                "UPDATE MENU SET nombre_producto = ?, precio_venta = ?, Identificador_categoria = ?, Descripcion = ? WHERE ID_menu = ?");
                        stmtMenu.setString(1, datos.nombre);
                        stmtMenu.setBigDecimal(2, datos.precio);
                        stmtMenu.setInt(3, datos.categoriaId);
                        stmtMenu.setString(4, datos.descripcion);
                        stmtMenu.setInt(5, idMenu);
                        stmtMenu.executeUpdate();

                        PreparedStatement borrar = con.prepareStatement("DELETE FROM CONTENIDO WHERE ID_menu = ?");
                        borrar.setInt(1, idMenu);
                        borrar.executeUpdate();

                        PreparedStatement buscarInsumo = con.prepareStatement(
                                "SELECT Codigo_insumo FROM INSUMO WHERE nombre_insumo = ?");
                        PreparedStatement insertar = con.prepareStatement(
                                "INSERT INTO CONTENIDO (ID_menu, Codigo_insumo) VALUES (?, ?)");

                        for (String insumo : datos.insumos) {
                            buscarInsumo.setString(1, insumo);
                            ResultSet res = buscarInsumo.executeQuery();
                            if (res.next()) {
                                int codigo = res.getInt("Codigo_insumo");
                                insertar.setInt(1, idMenu);
                                insertar.setInt(2, codigo);
                                insertar.executeUpdate();
                            }
                        }

                        con.commit();
                        ctx.status(200).result("Producto actualizado correctamente");

                    } catch (Exception e) {
                        e.printStackTrace();
                        ctx.status(500).result(
                                "Error al editar producto: " + e.getClass().getSimpleName() + " → " + e.getMessage());
                    }
                });

                delete("{id}", ctx -> {
                    int idMenu = Integer.parseInt(ctx.pathParam("id"));
                    try (Connection con = ConexionEC2.obtenerConexion()) {
                        con.setAutoCommit(false);

                        PreparedStatement borrarContenido = con.prepareStatement(
                                "DELETE FROM CONTENIDO WHERE ID_menu = ?");
                        borrarContenido.setInt(1, idMenu);
                        borrarContenido.executeUpdate();

                        PreparedStatement borrarMenu = con.prepareStatement(
                                "DELETE FROM MENU WHERE ID_menu = ?");
                        borrarMenu.setInt(1, idMenu);
                        int filas = borrarMenu.executeUpdate();

                        con.commit();

                        if (filas > 0) {
                            ctx.status(200).result("Producto eliminado correctamente");
                        } else {
                            ctx.status(404).result("Producto no encontrado");
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        ctx.status(500).result("Error al eliminar producto: " + e.getMessage());
                    }
                });
            });
            //

            //

        });
    }
}