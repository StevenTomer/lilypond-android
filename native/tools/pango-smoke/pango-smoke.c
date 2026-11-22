/* Smoke test: static GLib + Fontconfig + PangoFT2 + Cairo on Android, the
 * same library combination LilyPond uses.  Resolves a font through
 * Pango/Fontconfig, then draws text with cairo-ft into a PDF. */
#include <cairo.h>
#include <cairo-ft.h>
#include <cairo-pdf.h>
#include <fontconfig/fontconfig.h>
#include <pango/pangoft2.h>
#include <stdio.h>

int main(int argc, char **argv) {
    const char *out = argc > 1 ? argv[1] : "smoke.pdf";
    PangoFontMap *fm = pango_ft2_font_map_new();
    PangoContext *ctx = pango_font_map_create_context(fm);
    PangoFontDescription *d = pango_font_description_from_string("TeX Gyre Schola 20");
    PangoFont *f = pango_font_map_load_font(fm, ctx, d);
    PangoFontDescription *got = pango_font_describe(f);
    FT_Face face = pango_fc_font_lock_face(PANGO_FC_FONT(f));
    printf("pango %s, cairo %s\nresolved: %s (%s)\n", pango_version_string(),
           cairo_version_string(), pango_font_description_to_string(got),
           face ? face->family_name : "?");

    cairo_surface_t *s = cairo_pdf_surface_create(out, 300, 100);
    cairo_t *cr = cairo_create(s);
    cairo_font_face_t *cf = cairo_ft_font_face_create_for_ft_face(face, 0);
    cairo_set_font_face(cr, cf);
    cairo_set_font_size(cr, 24);
    cairo_move_to(cr, 10, 50);
    cairo_show_text(cr, "LilyPond on Android");
    cairo_destroy(cr);
    cairo_surface_finish(s);
    printf("pdf status: %s\n", cairo_status_to_string(cairo_surface_status(s)));
    return 0;
}
