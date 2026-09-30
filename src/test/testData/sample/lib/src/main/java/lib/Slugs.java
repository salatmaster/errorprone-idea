package lib;

/** Turns titles into URL slugs. */
public final class Slugs {
  private Slugs() {}

  public static String slugify(String title) {
    return title.trim().toLowerCase().replace(' ', '-');
  }
}
