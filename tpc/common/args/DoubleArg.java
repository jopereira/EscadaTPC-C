package Escada.tpc.common.args;

public class DoubleArg extends Arg {
   public double num =0;

   public DoubleArg(String arg, String name, String desc) {
      super(arg, name, desc, false, false);
   }

   public DoubleArg(String arg, String name, String desc, ArgDB db) {
      super(arg, name, desc, false, false, db);
   }

   public DoubleArg(String arg, String name, String desc, double def) {
      super(arg, name, desc, false, true);
      num = def;
   }

   public DoubleArg(String arg, String name, String desc,
		    double def, ArgDB db)
   {
      super(arg, name, desc, false, true, db);
      num = def;
   }

   // Customize to parse arguments.
   protected int parseMatch(String [] args, int a)
	 throws Arg.Exception
   {
      if (a == args.length) {
	 throw new Arg.Exception("Double argument missing value.", a);
      }
      try {
	 num = Double.valueOf(args[a]).doubleValue();
      }
      catch(NumberFormatException nfe) {
	 throw new Arg.Exception("Unable to parse double value (" +
				  args[a] + ").", a);
      }

      return(a+1);
   }

   public String value() { return(""+num); }
}
// arch-tag: 585adb58-df8d-4684-9a35-2d3c08756fbf
