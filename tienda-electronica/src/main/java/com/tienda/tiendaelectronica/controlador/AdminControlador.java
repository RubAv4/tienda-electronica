/* src/main/java/com/tienda/tiendaelectronica/controlador/AdminControlador.java */
package com.tienda.tiendaelectronica.controlador;

import com.tienda.tiendaelectronica.excepciones.RecursoNoEncontradoException;
import com.tienda.tiendaelectronica.modelo.Pedido;
import com.tienda.tiendaelectronica.modelo.Producto;
import com.tienda.tiendaelectronica.modelo.LogReponedor;
import com.tienda.tiendaelectronica.modelo.Categoria;
import com.tienda.tiendaelectronica.repositorio.CategoriaRepositorio;
import com.tienda.tiendaelectronica.repositorio.ProductoRepositorio;
import com.tienda.tiendaelectronica.servicio.PedidoServicio;
import com.tienda.tiendaelectronica.servicio.ProductoServicio;
import com.tienda.tiendaelectronica.servicio.LogReponedorServicio;
import com.tienda.tiendaelectronica.servicio.CategoriaServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class AdminControlador {

    private final PedidoServicio pedidoServicio;
    private final ProductoServicio productoServicio;
    private final LogReponedorServicio logReponedorServicio;

    // Servicios / repos para categorías y productos
    private final CategoriaServicio categoriaServicio;
    private final CategoriaRepositorio categoriaRepositorio;
    private final ProductoRepositorio productoRepositorio;

    // ---- DTO sencillo para crear productos ----
    public static class CrearProductoRequest {
        public Long categoriaId;
        public String nombre;
        public String descripcion;
        public BigDecimal precio;
        public Integer stock;
        public String imagenUrl;
    }

    // =========================================================
    // =============== DASHBOARD (ADMIN + REPONEDOR) ===========
    // =========================================================
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','REPONEDOR')")
    public Map<String, Object> obtenerDashboard() {
        Map<String, Object> data = new HashMap<>();

        try {
            List<Pedido> pedidos = pedidoServicio.listarTodos();
            data.put("pedidos", pedidos);
        } catch (Exception e) {
            data.put("pedidos", Collections.emptyList());
        }

        try {
            List<Producto> productos = productoServicio.listarTodos();
            data.put("productos", productos);
        } catch (Exception e) {
            data.put("productos", Collections.emptyList());
        }

        try {
            List<LogReponedor> logs = logReponedorServicio.listarTodos();
            data.put("logsReponedor", logs);
        } catch (Exception e) {
            data.put("logsReponedor", Collections.emptyList());
        }

        try {
            data.put("ventasPorUsuario", pedidoServicio.resumenVentasPorUsuario());
        } catch (Exception e) {
            data.put("ventasPorUsuario", Collections.emptyList());
        }

        return data;
    }

    // =========================================================
    // =================== CATEGORÍAS (ADMIN) ==================
    // =========================================================

    // Listar categorías (ADMIN + REPONEDOR, útil para combos en front)
    @GetMapping("/categorias")
    @PreAuthorize("hasAnyRole('ADMIN','REPONEDOR')")
    public List<Categoria> listarCategoriasAdmin() {
        return categoriaRepositorio.findAll();
    }

    // Crear nueva categoría (SOLO ADMIN)
    @PostMapping("/categorias")
    @PreAuthorize("hasRole('ADMIN')")
    public Categoria crearCategoria(@RequestBody Categoria categoria) {
        try {
            // Nos aseguramos de que es una categoría nueva
            categoria.setId(null);
            return categoriaServicio.crear(categoria);

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            // Si algo raro pasa, devolvemos 500 controlado
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error al crear la categoría",
                    e
            );
        }
    }

    // =========================================================
    // ================== PRODUCTOS (ADMIN) ====================
    // =========================================================

    // Crear nuevo producto (SOLO ADMIN)
    @PostMapping("/productos")
    @PreAuthorize("hasRole('ADMIN')")
    public Producto crearProducto(@RequestBody CrearProductoRequest req,
                                  Authentication auth) {

        if (req.categoriaId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "categoriaId es obligatorio"
            );
        }

        try {
            Categoria categoria = categoriaServicio.obtenerPorId(req.categoriaId);

            Producto producto = new Producto();
            producto.setNombre(req.nombre);
            producto.setDescripcion(req.descripcion);
            producto.setPrecio(req.precio);
            producto.setStock(req.stock != null ? req.stock : 0);
            producto.setImagenUrl(req.imagenUrl);
            producto.setActivo(true);
            producto.setCategoria(categoria);

            // ✅ Usamos el servicio para crear el producto
            Producto guardado = productoServicio.crear(producto);

            String username = (auth != null) ? auth.getName() : "system";
            logReponedorServicio.registrarAccion(
                    username,
                    guardado.getId(),
                    0,
                    "Creó producto desde panel admin"
            );

            return guardado;

        } catch (RecursoNoEncontradoException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error al crear el producto",
                    e
            );
        }
    }

    // =========================================================
    // ======== STOCK (ADMIN + REPONEDOR, YA EXISTENTE) =======
    // =========================================================
    @PutMapping("/productos/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMIN','REPONEDOR')")
    public Producto actualizarStock(@PathVariable Long id,
                                    @RequestParam("stock") Integer stock,
                                    Authentication auth) {

        // Obtener stock anterior
        Producto antes = productoServicio.obtenerPorId(id);
        Integer stockAnterior = antes.getStock();

        // Actualizar stock
        Producto despues = productoServicio.actualizarStock(id, stock);

        // Registrar log
        logReponedorServicio.registrarAccion(
                auth.getName(),
                id,
                stock - (stockAnterior != null ? stockAnterior : 0),
                "Actualizó stock de " + stockAnterior + " a " + stock
        );

        return despues;
    }

    // =========================================================
    // ====== ACTIVO / NO ACTIVO (ADMIN + REPONEDOR) ===========
    // =========================================================
    @PutMapping("/productos/{id}/activo")
    @PreAuthorize("hasAnyRole('ADMIN','REPONEDOR')")
    public Producto cambiarEstadoActivo(@PathVariable Long id,
                                        @RequestParam("activo") boolean activo,
                                        Authentication auth) {

        Producto producto = productoServicio.cambiarEstadoActivo(id, activo);

        String msg = activo
                ? "Marcó producto como disponible"
                : "Marcó producto como NO disponible";

        logReponedorServicio.registrarAccion(
                auth.getName(),
                id,
                0,
                msg
        );

        return producto;
    }

    // =========================================================
    // ============= ELIMINAR PRODUCTO (SOLO ADMIN) ============
    // =========================================================
    @DeleteMapping("/productos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void eliminarProducto(@PathVariable Long id, Authentication auth) {
        productoServicio.eliminarLogico(id);

        logReponedorServicio.registrarAccion(
                auth.getName(),
                id,
                0,
                "Eliminó (baja lógica) el producto"
        );
    }
}
