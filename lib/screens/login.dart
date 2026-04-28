import 'package:flutter/material.dart';
import 'home.dart';


class LoginScreen extends StatefulWidget{
    const LoginScreen({super.key});

    @override
    State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
    final userController = TextEditingController();
    final passController = TextEditingController();

    final String USER = "admin";
    final String PASS = "1234";

    void login(BuildContext context) {
        if (userController.text == USER && passController.text == PASS) {
            Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => HomeScreen()),
            );
        } else {
            ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text("Invalid username or password")),
            );
        }
    }

    @override
    void dispose() {
        userController.dispose();
        passController.dispose();
        super.dispose();
    }

    @override
    Widget build(BuildContext context) {
        return Scaffold(
            appBar: AppBar(title: Text("Login")),
            body: Padding(
                padding: EdgeInsets.all(16.0),
                child: Column(
                    children: [
                        TextField(
                            controller: userController,
                            decoration: InputDecoration(labelText: "Username")
                        ),
                        TextField(
                            controller: passController,
                            decoration: InputDecoration(labelText: "Password")
                        ),
                        SizedBox(height: 20),
                        ElevatedButton(
                            onPressed: () => login(context),
                            child: Text("Login"),
                        )
                    ],
                ),
            ),
        );
    }
}