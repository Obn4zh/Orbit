// WPF projects drop System.IO from implicit usings (Path clashes with System.Windows.Shapes.Path).
global using System.IO;
global using Path = System.IO.Path;
