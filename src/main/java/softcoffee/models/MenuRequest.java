package softcoffee.models;

import java.math.BigDecimal;
import java.util.List;

public class MenuRequest {
    public Integer id;
    public String nombre;
    public BigDecimal precio;
    public int categoriaId;
    public String descripcion;
    public String imagen;
    public List<String> insumos;
}