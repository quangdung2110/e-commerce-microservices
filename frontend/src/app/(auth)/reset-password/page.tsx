'use client';

import { useState, Suspense } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import * as z from 'zod';
import { Loader2, ArrowRight, Check, KeyRound } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form';
import { Input } from '@/components/ui/input';
import { AuthAPI } from '@/lib/api/auth';

const formSchema = z.object({
  newPassword: z.string().min(8, { message: 'Password must be at least 8 characters' }).max(128),
  confirmPassword: z.string()
}).refine((data) => data.newPassword === data.confirmPassword, {
  message: "Passwords don't match",
  path: ["confirmPassword"],
});

function ResetPasswordForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = searchParams.get('token') || '';
  
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState(false);

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      newPassword: '',
      confirmPassword: '',
    },
  });

  const passwordValue = form.watch('newPassword');
  const hasMinLength = passwordValue.length >= 8;
  const hasNumber = /\d/.test(passwordValue);
  const hasUpper = /[A-Z]/.test(passwordValue);

  async function onSubmit(values: z.infer<typeof formSchema>) {
    if (!token) {
      setError('Invalid or missing reset token.');
      return;
    }

    try {
      setIsLoading(true);
      setError(null);
      await AuthAPI.resetPassword({
        token,
        new_password: values.newPassword
      });
      setSuccess(true);
      setTimeout(() => {
        router.push('/login');
      }, 3000);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to reset password. The link might have expired.');
    } finally {
      setIsLoading(false);
    }
  }

  if (!token && !error) {
    return (
      <div className="glass-card p-8 rounded-3xl w-full mx-auto animate-in fade-in duration-500 text-center">
        <div className="text-destructive font-medium mb-4">Invalid Reset Link</div>
        <p className="text-muted-foreground text-sm mb-6">
          The password reset link is missing or invalid. Please request a new one.
        </p>
        <Link href="/forgot-password" className="w-full block">
          <Button className="w-full rounded-xl">Request New Link</Button>
        </Link>
      </div>
    );
  }

  return (
    <div className="glass-card p-8 rounded-3xl w-full mx-auto animate-in fade-in zoom-in duration-500">
      <div className="mb-8 text-center flex flex-col items-center">
        <div className="w-16 h-16 rounded-full bg-primary/20 flex items-center justify-center mb-4">
          <KeyRound className="w-8 h-8 text-primary" />
        </div>
        <h1 className="text-3xl font-bold tracking-tight mb-2">Create New Password</h1>
        <p className="text-muted-foreground text-sm max-w-sm">
          Your new password must be different from previous used passwords.
        </p>
      </div>

      {error && (
        <div className="mb-6 p-4 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm font-medium animate-in slide-in-from-top-2">
          {error}
        </div>
      )}

      {success ? (
        <div className="space-y-6 text-center animate-in slide-in-from-bottom-4">
          <div className="p-4 rounded-xl bg-green-500/10 border border-green-500/20 text-green-500 text-sm font-medium">
            Password reset successfully! Redirecting you to login...
          </div>
          <Link href="/login" className="w-full block">
            <Button className="w-full rounded-xl" variant="outline">Go to Login Now</Button>
          </Link>
        </div>
      ) : (
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <FormField
              control={form.control}
              name="newPassword"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="text-foreground/80">New Password</FormLabel>
                  <FormControl>
                    <Input 
                      type="password" 
                      placeholder="••••••••" 
                      {...field} 
                      className="glass bg-transparent focus-visible:ring-primary focus-visible:border-primary transition-all duration-300"
                    />
                  </FormControl>
                  <div className="mt-2 space-y-1">
                    <div className={`text-xs flex items-center ${hasMinLength ? 'text-green-500' : 'text-muted-foreground'}`}>
                      <Check className="w-3 h-3 mr-1" /> 8+ characters
                    </div>
                    <div className={`text-xs flex items-center ${hasNumber ? 'text-green-500' : 'text-muted-foreground'}`}>
                      <Check className="w-3 h-3 mr-1" /> At least 1 number
                    </div>
                    <div className={`text-xs flex items-center ${hasUpper ? 'text-green-500' : 'text-muted-foreground'}`}>
                      <Check className="w-3 h-3 mr-1" /> At least 1 uppercase letter
                    </div>
                  </div>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="confirmPassword"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="text-foreground/80">Confirm New Password</FormLabel>
                  <FormControl>
                    <Input 
                      type="password" 
                      placeholder="••••••••" 
                      {...field} 
                      className="glass bg-transparent focus-visible:ring-primary focus-visible:border-primary transition-all duration-300"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <Button 
              type="submit" 
              className="w-full group glow transition-all duration-500 rounded-xl"
              disabled={isLoading || !hasMinLength}
            >
              {isLoading ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <>
                  Reset Password
                  <ArrowRight className="ml-2 h-4 w-4 group-hover:translate-x-1 transition-transform" />
                </>
              )}
            </Button>
          </form>
        </Form>
      )}
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={
      <div className="flex justify-center items-center h-64">
        <Loader2 className="w-8 h-8 animate-spin text-primary" />
      </div>
    }>
      <ResetPasswordForm />
    </Suspense>
  );
}
