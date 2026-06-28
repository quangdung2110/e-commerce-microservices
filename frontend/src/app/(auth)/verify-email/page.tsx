'use client';

import { useState, useEffect, Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import * as z from 'zod';
import { Loader2, ArrowRight, ShieldCheck, RefreshCw } from 'lucide-react';

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
  otp: z.string().length(6, { message: 'OTP must be exactly 6 digits' }).regex(/^\d+$/, 'Must be only digits'),
});

function VerifyEmailForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const emailParam = searchParams.get('email') || '';
  
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [resendLoading, setResendLoading] = useState(false);
  const [resendSuccess, setResendSuccess] = useState(false);

  useEffect(() => {
    if (!emailParam) {
      router.push('/register');
    }
  }, [emailParam, router]);

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      otp: '',
    },
  });

  async function onSubmit(values: z.infer<typeof formSchema>) {
    try {
      setIsLoading(true);
      setError(null);
      await AuthAPI.verifyEmail({
        email: emailParam,
        otp: values.otp
      });
      // Verification success, redirect to login
      router.push('/login');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to verify email. Invalid OTP.');
    } finally {
      setIsLoading(false);
    }
  }

  async function handleResend() {
    try {
      setResendLoading(true);
      setError(null);
      setResendSuccess(false);
      await AuthAPI.resendOTP({ email: emailParam });
      setResendSuccess(true);
      setTimeout(() => setResendSuccess(false), 3000);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to resend OTP.');
    } finally {
      setResendLoading(false);
    }
  }

  if (!emailParam) return null;

  return (
    <div className="glass-card p-8 rounded-3xl w-full mx-auto animate-in fade-in zoom-in duration-500">
      <div className="mb-8 text-center flex flex-col items-center">
        <div className="w-16 h-16 rounded-full bg-primary/20 flex items-center justify-center mb-4">
          <ShieldCheck className="w-8 h-8 text-primary" />
        </div>
        <h1 className="text-3xl font-bold tracking-tight mb-2">Check your email</h1>
        <p className="text-muted-foreground text-sm max-w-sm">
          We've sent a 6-digit verification code to <span className="font-semibold text-foreground">{emailParam}</span>
        </p>
      </div>

      {error && (
        <div className="mb-6 p-4 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm font-medium animate-in slide-in-from-top-2">
          {error}
        </div>
      )}

      {resendSuccess && (
        <div className="mb-6 p-4 rounded-xl bg-green-500/10 border border-green-500/20 text-green-500 text-sm font-medium animate-in slide-in-from-top-2">
          A new code has been sent to your email.
        </div>
      )}

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          <FormField
            control={form.control}
            name="otp"
            render={({ field }) => (
              <FormItem>
                <FormLabel className="text-foreground/80">Verification Code</FormLabel>
                <FormControl>
                  <Input 
                    placeholder="123456" 
                    {...field} 
                    className="glass bg-transparent text-center tracking-[0.5em] text-lg font-mono focus-visible:ring-primary focus-visible:border-primary transition-all duration-300"
                    maxLength={6}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button 
            type="submit" 
            className="w-full group glow transition-all duration-500 rounded-xl"
            disabled={isLoading || form.watch('otp').length !== 6}
          >
            {isLoading ? (
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            ) : (
              <>
                Verify Email
                <ArrowRight className="ml-2 h-4 w-4 group-hover:translate-x-1 transition-transform" />
              </>
            )}
          </Button>
        </form>
      </Form>

      <div className="mt-8 text-center text-sm text-muted-foreground flex items-center justify-center space-x-2">
        <span>Didn't receive the code?</span>
        <Button 
          variant="link" 
          className="p-0 h-auto text-primary hover:text-glow transition-all"
          onClick={handleResend}
          disabled={resendLoading}
        >
          {resendLoading ? (
            <Loader2 className="w-4 h-4 animate-spin" />
          ) : (
            <span className="flex items-center">
              Resend <RefreshCw className="ml-1 w-3 h-3" />
            </span>
          )}
        </Button>
      </div>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={
      <div className="flex justify-center items-center h-64">
        <Loader2 className="w-8 h-8 animate-spin text-primary" />
      </div>
    }>
      <VerifyEmailForm />
    </Suspense>
  );
}
