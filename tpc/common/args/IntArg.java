package Escada.tpc.common.args;

public class IntArg extends Arg {
  public int num =0;

  public IntArg(String arg, String name, String desc) {
	 super(arg, name, desc, false, false);
  }

  public IntArg(String arg, String name, String desc, ArgDB db) {
	 super(arg, name, desc, false, false, db);
  }

  public IntArg(String arg, String name, String desc, int def) {
	 super(arg, name, desc, false, true);
	 num = def;
  }

  public IntArg(String arg, String name, String desc, int def, ArgDB db) {
	 super(arg, name, desc, false, true, db);
	 num = def;
  }

  // Customize to parse arguments.
  protected int parseMatch(String [] args, int a)
		 throws Arg.Exception
  {
	 if (a == args.length) {
		throw new Arg.Exception("Integer argument missing value.", a);
	 }
	 try {
		num = Integer.parseInt(args[a]);
	 }
	 catch(NumberFormatException nfe) {
		throw new Arg.Exception("Unable to parse integer value (" + 
										args[a] + ").", a);
	 }

	 return(a+1);
  }
	
  public String value() { return(""+num); }
}

// arch-tag: 8eb1d73a-c4cd-442f-b00f-82fa18b9be57
